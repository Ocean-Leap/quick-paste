package com.quickpaste.service;

import com.quickpaste.config.QuickPasteConfig;
import com.quickpaste.error.ApiException;
import com.quickpaste.model.Channel;
import com.quickpaste.model.StoredFile;
import com.quickpaste.model.User;
import com.quickpaste.model.dto.Dtos;
import com.quickpaste.repository.ChannelMemberRepository;
import com.quickpaste.repository.ChannelRepository;
import com.quickpaste.repository.StoredFileRepository;
import com.quickpaste.model.ChannelMember;
import com.quickpaste.util.Hashing;
import com.quickpaste.util.Ids;
import io.micronaut.http.multipart.CompletedFileUpload;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Simple (non-resumable) file storage (SPEC §5.4). Blobs live under
 * QP_STORAGE_DIR/{channelId}/{fileId} — server-generated ULID names only,
 * never client filenames (§9.7).
 */
@Singleton
public class FileService {

    private static final Logger LOG = LoggerFactory.getLogger(FileService.class);

    private final QuickPasteConfig config;
    private final StoredFileRepository files;
    private final ChannelMemberRepository members;
    private final ChannelRepository channels;
    private final UserLocks userLocks;

    public FileService(QuickPasteConfig config, StoredFileRepository files, ChannelMemberRepository members,
                       ChannelRepository channels, UserLocks userLocks) {
        this.config = config;
        this.files = files;
        this.members = members;
        this.channels = channels;
        this.userLocks = userLocks;
    }

    public Dtos.FileUploadedDto upload(User user, String channelId, CompletedFileUpload upload) {
        requireActiveMember(user.id(), channelId);
        rejectExternal(channelId);

        long declaredSize = upload.getSize();
        long maxFileBytes = config.getMaxFileMb() * 1024L * 1024L;
        long simpleLimitBytes = config.getSimpleUploadMb() * 1024L * 1024L;
        if (declaredSize > maxFileBytes) {
            throw ApiException.payloadTooLarge(
                    "File exceeds " + config.getMaxFileMb() + " MB (QP_MAX_FILE_MB).");
        }
        if (declaredSize > simpleLimitBytes) {
            throw ApiException.payloadTooLarge(
                    "File exceeds the simple upload limit of " + config.getSimpleUploadMb()
                            + " MB (QP_SIMPLE_UPLOAD_MB). Resumable uploads arrive in M3.");
        }
        long quotaBytes = config.getUserQuotaMb() * 1024L * 1024L;
        // Fast pre-check (unlocked): reject obviously over-quota uploads before streaming.
        if (files.totalSizeByUser(user.id()) + declaredSize > quotaBytes) {
            throw ApiException.quotaExceeded(
                    "Storage quota of " + config.getUserQuotaMb() + " MB (QP_USER_QUOTA_MB) exceeded.");
        }

        String fileId = Ids.newUlid();
        Path dir = channelDir(channelId);
        Path tmp = dir.resolve(fileId + ".part");
        Path target = dir.resolve(fileId);
        String checksum;
        long actualSize;
        try {
            Files.createDirectories(dir);
            try (InputStream in = upload.getInputStream();
                 OutputStream out = Files.newOutputStream(tmp)) {
                checksum = Hashing.copyAndHash(in, out);
            }
            actualSize = Files.size(tmp);
            if (actualSize > simpleLimitBytes) {
                Files.deleteIfExists(tmp);
                throw ApiException.payloadTooLarge("File exceeds the simple upload limit.");
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best-effort cleanup; the sweep removes stale .part files
            }
            LOG.error("Blob write failed for channel {}: {}", channelId, e.getMessage());
            throw new ApiException(500, "storage_error", "Failed to store the file.", java.util.Map.of());
        }

        // Authoritative quota check + commit, serialized per user so concurrent uploads
        // cannot all pass against the same stale usage figure (FR-43).
        synchronized (userLocks.lockFor(user.id())) {
            try {
                if (files.totalSizeByUser(user.id()) + actualSize > quotaBytes) {
                    Files.deleteIfExists(tmp);
                    throw ApiException.quotaExceeded(
                            "Storage quota of " + config.getUserQuotaMb() + " MB (QP_USER_QUOTA_MB) exceeded.");
                }
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOG.error("Blob commit failed for channel {}: {}", channelId, e.getMessage());
                throw new ApiException(500, "storage_error", "Failed to store the file.", java.util.Map.of());
            }
            Instant now = Instant.now();
            StoredFile stored = new StoredFile(
                    fileId,
                    channelId,
                    user.id(),
                    sanitizeName(upload.getFilename()),
                    mimeOf(upload),
                    actualSize,
                    target.toString(),
                    checksum,
                    now,
                    now.plus(Duration.ofHours(config.getFileTtlHours())));
            files.save(stored);
            return new Dtos.FileUploadedDto(stored.id(), stored.name(), stored.size(), stored.mime());
        }
    }

    public Dtos.FileMetaDto meta(User user, String channelId, String fileId) {
        return Dtos.FileMetaDto.from(liveFile(user, channelId, fileId));
    }

    public record Download(StoredFile file, Path path) {
    }

    public Download download(User user, String channelId, String fileId) {
        StoredFile file = liveFile(user, channelId, fileId);
        Path path = Path.of(file.storagePath());
        if (!Files.isReadable(path)) {
            throw ApiException.notFound();
        }
        return new Download(file, path);
    }

    public void deleteBlobAndRow(StoredFile file) {
        try {
            Files.deleteIfExists(Path.of(file.storagePath()));
            Files.deleteIfExists(thumbPath(file));
        } catch (IOException e) {
            LOG.warn("Failed to delete blob {}: {}", file.id(), e.getMessage());
        }
        if (files.existsById(file.id())) {
            files.deleteById(file.id());
        }
    }

    public void deleteChannelDir(String channelId) {
        deleteRecursively(channelDir(channelId));
        deleteRecursively(thumbDir(channelId));
    }

    private void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.warn("Failed to delete {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.warn("Failed to walk blob dir {}: {}", dir, e.getMessage());
        }
    }

    public Path storageRoot() {
        return Path.of(config.getStorageDir());
    }

    public Path channelDir(String channelId) {
        return storageRoot().resolve(channelId);
    }

    public Path thumbDir(String channelId) {
        return storageRoot().resolve(".thumbs").resolve(channelId);
    }

    public Path thumbPath(StoredFile file) {
        return thumbDir(file.channelId()).resolve(file.id() + ".jpg");
    }

    /** RFC 6266 Content-Disposition with an ASCII fallback and UTF-8 filename*. */
    public static String contentDisposition(String name) {
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "'").replace("\\", "_");
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    /** Membership-checked, non-expired file lookup — 404 otherwise (§9.5). */
    public StoredFile liveFile(User user, String channelId, String fileId) {
        requireActiveMember(user.id(), channelId);
        return files.findByIdAndChannelId(fileId, channelId)
                .filter(f -> f.expiresAt().isAfter(Instant.now()))
                .orElseThrow(ApiException::notFound);
    }

    private void rejectExternal(String channelId) {
        if (channels.findById(channelId).map(c -> Channel.TYPE_EXTERNAL.equals(c.type())).orElse(false)) {
            throw ApiException.validation(
                    "External sink channels accept text only (Steward Mind note/quote/link).");
        }
    }

    private void requireActiveMember(String userId, String channelId) {
        Optional<ChannelMember> member = members.findByChannelIdAndUserId(channelId, userId)
                .filter(m -> ChannelMember.STATUS_ACTIVE.equals(m.status()));
        if (member.isEmpty()) {
            throw ApiException.notFound();
        }
    }

    public static String sanitizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "file";
        }
        String cleaned = raw.replaceAll("[\\x00-\\x1F\\x7F/\\\\]", "_").trim();
        if (cleaned.isEmpty()) {
            return "file";
        }
        return cleaned.length() > 255 ? cleaned.substring(0, 255) : cleaned;
    }

    private static String mimeOf(CompletedFileUpload upload) {
        String mime = upload.getContentType().map(Object::toString).orElse("application/octet-stream");
        return mime.length() > 128 ? mime.substring(0, 128) : mime;
    }
}
