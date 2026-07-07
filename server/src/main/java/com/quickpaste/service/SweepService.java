package com.quickpaste.service;

import com.quickpaste.config.QuickPasteConfig;
import com.quickpaste.model.ExportJob;
import com.quickpaste.model.StoredFile;
import com.quickpaste.repository.AuthSessionRepository;
import com.quickpaste.repository.ClipRepository;
import com.quickpaste.repository.ExportJobRepository;
import com.quickpaste.repository.StoredFileRepository;
import com.quickpaste.security.RateLimiter;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * TTL sweep + GC (SPEC §12.6): expired clips, expired files + blobs, abandoned uploads,
 * on-disk orphans, expired sessions and export archives. Logs metadata counts only.
 */
@Singleton
public class SweepService {

    private static final Logger LOG = LoggerFactory.getLogger(SweepService.class);

    private final QuickPasteConfig config;
    private final ClipRepository clips;
    private final StoredFileRepository files;
    private final AuthSessionRepository sessions;
    private final ExportJobRepository exports;
    private final com.quickpaste.repository.UploadSessionRepository uploads;
    private final FileService fileService;
    private final AccountService accountService;
    private final UploadService uploadService;
    private final IdempotencyCache idempotency;
    private final RateLimiter rateLimiter;

    public SweepService(QuickPasteConfig config, ClipRepository clips, StoredFileRepository files,
                        AuthSessionRepository sessions, ExportJobRepository exports,
                        com.quickpaste.repository.UploadSessionRepository uploads,
                        FileService fileService, AccountService accountService,
                        UploadService uploadService,
                        IdempotencyCache idempotency, RateLimiter rateLimiter) {
        this.config = config;
        this.clips = clips;
        this.files = files;
        this.sessions = sessions;
        this.exports = exports;
        this.uploads = uploads;
        this.fileService = fileService;
        this.accountService = accountService;
        this.uploadService = uploadService;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
    }

    @Scheduled(fixedDelay = "${quickpaste.sweep-interval-min}m", initialDelay = "1m")
    public void sweep() {
        Instant now = Instant.now();
        long expiredClips = clips.deleteExpired(now);

        List<StoredFile> expiredFiles = files.findExpired(now);
        for (StoredFile file : expiredFiles) {
            fileService.deleteBlobAndRow(file); // row delete cascades any clips still referencing it
        }

        Instant abandonedCutoff = now.minus(Duration.ofHours(config.getUploadIdleGcHours()));
        List<StoredFile> abandoned = files.findUnreferencedBefore(abandonedCutoff);
        for (StoredFile file : abandoned) {
            fileService.deleteBlobAndRow(file);
        }

        // Idle resumable uploads: session row + .part data (SPEC §8.6).
        List<com.quickpaste.model.UploadSession> idleUploads = uploads.findIdleBefore(abandonedCutoff);
        for (com.quickpaste.model.UploadSession idle : idleUploads) {
            uploadService.discard(idle);
        }

        int diskOrphans = sweepDiskOrphans(abandonedCutoff);
        long expiredSessions = sessions.deleteExpired(now);

        List<ExportJob> expiredExports = exports.findExpired(now);
        for (ExportJob job : expiredExports) {
            accountService.deleteArchive(job);
        }

        idempotency.evictOlderThan(config.getIdempotencyTtlSec());
        rateLimiter.evictStale();

        LOG.info("Sweep done: expiredClips={} expiredFiles={} abandonedFiles={} idleUploads={} "
                        + "diskOrphans={} expiredSessions={} expiredExports={}",
                expiredClips, expiredFiles.size(), abandoned.size(), idleUploads.size(),
                diskOrphans, expiredSessions, expiredExports.size());
    }

    /** Blobs on disk without a files row (crash residue) and stale .part files. */
    private int sweepDiskOrphans(Instant cutoff) {
        Path root = fileService.storageRoot();
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> channelDirs = Files.list(root)) {
            for (Path dir : channelDirs.toList()) {
                String dirName = dir.getFileName().toString();
                if (!Files.isDirectory(dir) || dirName.equals("exports") || dirName.startsWith(".")) {
                    continue;
                }
                try (Stream<Path> blobs = Files.list(dir)) {
                    for (Path blob : blobs.toList()) {
                        if (Files.isDirectory(blob)) {
                            continue;
                        }
                        String name = blob.getFileName().toString();
                        String fileId = name.endsWith(".part") ? name.substring(0, name.length() - 5) : name;
                        boolean stale = Files.getLastModifiedTime(blob).toInstant().isBefore(cutoff);
                        // .part files of live upload sessions are protected by the mtime check
                        // (active uploads keep touching the file).
                        if (stale && files.findById(fileId).isEmpty() && uploads.findById(fileId).isEmpty()) {
                            Files.deleteIfExists(blob);
                            removed++;
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("Disk-orphan sweep failed: {}", e.getMessage());
        }
        return removed;
    }
}
