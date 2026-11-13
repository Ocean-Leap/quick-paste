package com.quickpaste.service;

import com.quickpaste.config.QuickPasteConfig;
import com.quickpaste.error.ApiException;
import com.quickpaste.model.StoredFile;
import com.quickpaste.model.UploadSession;
import com.quickpaste.model.User;
import com.quickpaste.repository.StoredFileRepository;
import com.quickpaste.repository.UploadSessionRepository;
import com.quickpaste.util.Ids;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resumable uploads, tus-1.0-compatible (SPEC §8.6): create with Upload-Length/Upload-Metadata,
 * PATCH chunks at Upload-Offset, HEAD for resume. The offset is the size of the on-disk
 * {@code .part} file, so uploads survive server restarts. The session id becomes the fileId
 * on finalize; quota is checked at creation and re-checked before finalize (FR-25/FR-43).
 */
@Singleton
public class UploadService {

    private static final Logger LOG = LoggerFactory.getLogger(UploadService.class);

    private final QuickPasteConfig config;
    private final UploadSessionRepository sessions;
    private final StoredFileRepository files;
    private final ChannelService channelService;
    private final FileService fileService;
    private final UserLocks userLocks;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public UploadService(QuickPasteConfig config, UploadSessionRepository sessions,
                         StoredFileRepository files, ChannelService channelService,
                         FileService fileService, UserLocks userLocks) {
        this.config = config;
        this.sessions = sessions;
        this.files = files;
        this.channelService = channelService;
        this.fileService = fileService;
        this.userLocks = userLocks;
    }

    public UploadSession create(User user, String channelId, long uploadLength,
                                @Nullable String uploadMetadata) {
        channelService.requireActiveMember(user.id(), channelId);
        channelService.rejectFilesOnExternal(channelId);
        if (uploadLength <= 0) {
            throw ApiException.validation("Upload-Length must be a positive integer.");
        }
        if (uploadLength > config.getMaxFileMb() * 1024L * 1024L) {
            throw ApiException.payloadTooLarge(
                    "File exceeds " + config.getMaxFileMb() + " MB (QP_MAX_FILE_MB).");
        }
        long used = files.totalSizeByUser(user.id());
        if (used + uploadLength > config.getUserQuotaMb() * 1024L * 1024L) {
            throw ApiException.quotaExceeded(
                    "Storage quota of " + config.getUserQuotaMb() + " MB (QP_USER_QUOTA_MB) exceeded.");
        }
        Map<String, String> metadata = parseTusMetadata(uploadMetadata);
        Instant now = Instant.now();
        UploadSession session = new UploadSession(
                Ids.newUlid(),
                channelId,
                user.id(),
                FileService.sanitizeName(metadata.get("filename")),
                sanitizeMime(metadata.get("filetype")),
                uploadLength,
                now,
                now);
        try {
            Files.createDirectories(fileService.channelDir(channelId));
            Files.createFile(partPath(session));
        } catch (IOException e) {
            LOG.error("Failed to create upload part file: {}", e.getMessage());
            throw new ApiException(500, "storage_error", "Failed to start the upload.", Map.of());
        }
        sessions.save(session);
        return session;
    }

    public record UploadState(UploadSession session, long offset) {
    }

    public UploadState state(User user, String channelId, String uploadId) {
        channelService.requireActiveMember(user.id(), channelId);
        UploadSession session = sessions.findByIdAndChannelId(uploadId, channelId)
                .orElseThrow(ApiException::notFound);
        return new UploadState(session, currentOffset(session));
    }

    public record PatchResult(long newOffset, @Nullable StoredFile finalized) {
    }

    public PatchResult appendChunk(User user, String channelId, String uploadId,
                                   long claimedOffset, InputStream body) {
        channelService.requireActiveMember(user.id(), channelId);
        UploadSession session = sessions.findByIdAndChannelId(uploadId, channelId)
                .orElseThrow(ApiException::notFound);
        // The lock entry lives as long as the session (removed on finalize/discard/abort).
        // Removing it per-request would let a queued waiter and a fresh caller hold
        // different monitors and append concurrently.
        Object lock = locks.computeIfAbsent(session.id(), k -> new Object());
        synchronized (lock) {
            return doAppend(session, claimedOffset, body);
        }
    }

    private PatchResult doAppend(UploadSession session, long claimedOffset, InputStream body) {
        Path part = partPath(session);
        long current = currentOffset(session);
        if (claimedOffset != current) {
            throw ApiException.conflict("Upload-Offset mismatch: expected " + current + ".");
        }
        long remaining = session.declaredSize() - current;
        long written;
        try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.APPEND)) {
            written = copyAtMost(body, out, remaining + 1); // +1 to detect overrun
        } catch (IOException e) {
            LOG.warn("Chunk write failed for upload {}: {}", session.id(), e.getMessage());
            throw new ApiException(500, "storage_error", "Failed to write the chunk.", Map.of());
        }
        if (written > remaining) {
            truncate(part, current);
            throw ApiException.conflict("Chunk exceeds the declared Upload-Length.");
        }
        long newOffset = current + written;
        sessions.touch(session.id(), Instant.now());
        if (newOffset < session.declaredSize()) {
            return new PatchResult(newOffset, null);
        }
        return new PatchResult(newOffset, finalize(session, part));
    }

    private StoredFile finalize(UploadSession session, Path part) {
        String checksum;
        try (InputStream in = Files.newInputStream(part)) {
            checksum = hashStream(in);
        } catch (IOException e) {
            abort(session, part);
            throw new ApiException(500, "storage_error", "Failed to checksum the upload.", Map.of());
        }
        // Quota re-check + commit are serialized per user so parallel finalizes cannot
        // both pass against the same stale usage figure (FR-25/FR-43).
        synchronized (userLocks.lockFor(session.createdBy())) {
            long used = files.totalSizeByUser(session.createdBy());
            if (used + session.declaredSize() > config.getUserQuotaMb() * 1024L * 1024L) {
                abort(session, part);
                throw ApiException.quotaExceeded("Storage quota exceeded at finalize.");
            }
            Path target = fileService.channelDir(session.channelId()).resolve(session.id());
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                abort(session, part);
                throw new ApiException(500, "storage_error", "Failed to finalize the upload.", Map.of());
            }
            Instant now = Instant.now();
            StoredFile stored = new StoredFile(
                    session.id(),
                    session.channelId(),
                    session.createdBy(),
                    session.name(),
                    session.mime(),
                    session.declaredSize(),
                    target.toString(),
                    checksum,
                    now,
                    now.plus(Duration.ofHours(config.getFileTtlHours())));
            files.save(stored);
            sessions.deleteById(session.id());
            locks.remove(session.id());
            return stored;
        }
    }

    /** Removes an idle session and its partial data (called by the sweep, §12.6). */
    public void discard(UploadSession session) {
        try {
            Files.deleteIfExists(partPath(session));
        } catch (IOException e) {
            LOG.warn("Failed to delete stale part for upload {}: {}", session.id(), e.getMessage());
        }
        if (sessions.existsById(session.id())) {
            sessions.deleteById(session.id());
        }
        locks.remove(session.id());
    }

    private void abort(UploadSession session, Path part) {
        try {
            Files.deleteIfExists(part);
        } catch (IOException ignored) {
            // best effort; the sweep collects leftovers
        }
        sessions.deleteById(session.id());
        locks.remove(session.id());
    }

    private long currentOffset(UploadSession session) {
        try {
            Path part = partPath(session);
            return Files.exists(part) ? Files.size(part) : 0;
        } catch (IOException e) {
            throw new ApiException(500, "storage_error", "Failed to read upload state.", Map.of());
        }
    }

    private Path partPath(UploadSession session) {
        return fileService.channelDir(session.channelId()).resolve(session.id() + ".part");
    }

    private static void truncate(Path part, long size) {
        try (FileChannel channel = FileChannel.open(part, StandardOpenOption.WRITE)) {
            channel.truncate(size);
        } catch (IOException e) {
            LOG.warn("Failed to truncate over-long chunk on {}: {}", part, e.getMessage());
        }
    }

    private static long copyAtMost(InputStream in, OutputStream out, long max) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while (total < max && (n = in.read(buf, 0, (int) Math.min(buf.length, max - total))) != -1) {
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    private static String hashStream(InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                digest.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Parses tus Upload-Metadata: comma-separated {@code key base64(value)} pairs. */
    static Map<String, String> parseTusMetadata(@Nullable String header) {
        Map<String, String> result = new HashMap<>();
        if (header == null || header.isBlank()) {
            return result;
        }
        for (String pair : header.split(",")) {
            String[] parts = pair.trim().split("\\s+", 2);
            if (parts.length == 0 || parts[0].isEmpty()) {
                continue;
            }
            String value = "";
            if (parts.length == 2) {
                try {
                    value = new String(Base64.getDecoder().decode(parts[1]),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    continue; // malformed pair — ignore per tus leniency
                }
            }
            result.put(parts[0], value);
        }
        return result;
    }

    private static String sanitizeMime(@Nullable String mime) {
        if (mime == null || mime.isBlank() || !mime.matches("[\\w.+-]+/[\\w.+-]+")) {
            return "application/octet-stream";
        }
        return mime.length() > 128 ? mime.substring(0, 128) : mime;
    }
}
