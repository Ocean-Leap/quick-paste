package com.quickpaste.service;

import com.quickpaste.config.QuickPasteConfig;
import com.quickpaste.error.ApiException;
import com.quickpaste.model.Channel;
import com.quickpaste.model.Clip;
import com.quickpaste.model.ExportJob;
import com.quickpaste.model.StoredFile;
import com.quickpaste.model.User;
import com.quickpaste.model.dto.Dtos;
import com.quickpaste.repository.ApiTokenRepository;
import com.quickpaste.repository.AuthSessionRepository;
import com.quickpaste.repository.ChannelRepository;
import com.quickpaste.repository.ClipRepository;
import com.quickpaste.repository.ExportJobRepository;
import com.quickpaste.repository.StoredFileRepository;
import com.quickpaste.repository.UserRepository;
import com.quickpaste.util.Ids;
import com.quickpaste.ws.WsRegistry;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Account lifecycle (SPEC §5.12): sign-out-everywhere, cascade delete, and the async
 * data-export job (archive under QP_STORAGE_DIR/exports/, GC'd after QP_EXPORT_TTL_HOURS).
 */
@Singleton
public class AccountService {

    private static final Logger LOG = LoggerFactory.getLogger(AccountService.class);

    private final QuickPasteConfig config;
    private final UserRepository users;
    private final ChannelRepository channels;
    private final ClipRepository clips;
    private final StoredFileRepository files;
    private final ExportJobRepository exports;
    private final AuthSessionRepository sessions;
    private final ApiTokenRepository tokens;
    private final ChannelService channelService;
    private final FileService fileService;
    private final WsRegistry ws;
    private final ObjectMapper mapper;
    private final ExecutorService executor;

    public AccountService(QuickPasteConfig config, UserRepository users, ChannelRepository channels,
                          ClipRepository clips, StoredFileRepository files, ExportJobRepository exports,
                          AuthSessionRepository sessions, ApiTokenRepository tokens,
                          ChannelService channelService, FileService fileService, WsRegistry ws,
                          ObjectMapper mapper, @Named(TaskExecutors.BLOCKING) ExecutorService executor) {
        this.config = config;
        this.users = users;
        this.channels = channels;
        this.clips = clips;
        this.files = files;
        this.exports = exports;
        this.sessions = sessions;
        this.tokens = tokens;
        this.channelService = channelService;
        this.fileService = fileService;
        this.ws = ws;
        this.mapper = mapper;
        this.executor = executor;
    }

    public void logoutAll(User user) {
        sessions.revokeAllForUser(user.id(), Instant.now());
    }

    /** FR-36/FR-46: cascade delete — sockets, blobs, owned channels, then the user row. */
    public void deleteUser(User target) {
        ws.closeUserSessions(target.id());
        for (StoredFile file : files.findByCreatedBy(target.id())) {
            fileService.deleteBlobAndRow(file);
        }
        for (Channel owned : channels.findByCreatedBy(target.id())) {
            fileService.deleteChannelDir(owned.id());
            ws.channelDeleted(owned.id());
        }
        for (ExportJob job : exports.findByUserId(target.id())) {
            deleteArchive(job);
        }
        tokens.revokeAllForUser(target.id(), Instant.now());
        // The FK cascades remove owned channels, memberships, clips, files, tokens,
        // sessions, and export jobs with this row.
        users.deleteById(target.id());
    }

    public Dtos.ExportJobDto requestExport(User user) {
        ExportJob job = new ExportJob(Ids.newUlid(), user.id(), ExportJob.STATUS_PENDING,
                null, Instant.now(), null);
        exports.save(job);
        executor.execute(() -> runExport(job.id(), user));
        return Dtos.ExportJobDto.from(job);
    }

    public Dtos.ExportJobDto exportStatus(User user, String jobId) {
        return Dtos.ExportJobDto.from(
                exports.findByIdAndUserId(jobId, user.id()).orElseThrow(ApiException::notFound));
    }

    public Path exportArchive(User user, String jobId) {
        ExportJob job = exports.findByIdAndUserId(jobId, user.id()).orElseThrow(ApiException::notFound);
        if (!ExportJob.STATUS_DONE.equals(job.status()) || job.storagePath() == null) {
            throw ApiException.conflict("Export is not ready (status: " + job.status() + ").");
        }
        Path path = Path.of(job.storagePath());
        if (!Files.isReadable(path)) {
            throw ApiException.notFound();
        }
        return path;
    }

    private void runExport(String jobId, User user) {
        exports.updateResult(jobId, ExportJob.STATUS_RUNNING, null, null);
        Path dir = fileService.storageRoot().resolve("exports");
        Path archive = dir.resolve(jobId + ".zip");
        try {
            Files.createDirectories(dir);
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                writeEntry(zip, "user.json", mapper.writeValueAsString(Dtos.UserDto.from(user)));
                List<Dtos.ChannelDto> channelDtos = channelService.listForUser(user);
                writeEntry(zip, "channels.json", mapper.writeValueAsString(channelDtos));
                for (Dtos.ChannelDto channel : channelDtos) {
                    List<Dtos.ClipDto> clipDtos = clips.findAllByChannel(channel.id()).stream()
                            .map(this::clipDto)
                            .toList();
                    writeEntry(zip, "clips-" + channel.id() + ".json", mapper.writeValueAsString(clipDtos));
                    List<StoredFile> channelFiles = files.findByChannelId(channel.id());
                    writeEntry(zip, "files-" + channel.id() + ".json", mapper.writeValueAsString(
                            channelFiles.stream().map(Dtos.FileMetaDto::from).toList()));
                    for (StoredFile file : channelFiles) {
                        Path blob = Path.of(file.storagePath());
                        if (Files.isReadable(blob)) {
                            zip.putNextEntry(new ZipEntry("blobs/" + channel.id() + "/" + file.id()));
                            try (InputStream in = Files.newInputStream(blob)) {
                                in.transferTo(zip);
                            }
                            zip.closeEntry();
                        }
                    }
                }
            }
            Instant expiresAt = Instant.now().plus(Duration.ofHours(config.getExportTtlHours()));
            exports.updateResult(jobId, ExportJob.STATUS_DONE, archive.toString(), expiresAt);
        } catch (Exception e) {
            LOG.error("Export job {} failed: {}", jobId, e.getMessage());
            try {
                Files.deleteIfExists(archive);
            } catch (IOException ignored) {
                // best effort; the sweep also prunes expired/failed archives
            }
            exports.updateResult(jobId, ExportJob.STATUS_FAILED, null, null);
        }
    }

    public void deleteArchive(ExportJob job) {
        if (job.storagePath() != null) {
            try {
                Files.deleteIfExists(Path.of(job.storagePath()));
            } catch (IOException e) {
                LOG.warn("Failed to delete export archive {}: {}", job.id(), e.getMessage());
            }
        }
        if (exports.existsById(job.id())) {
            exports.deleteById(job.id());
        }
    }

    private Dtos.ClipDto clipDto(Clip clip) {
        StoredFile file = clip.fileId() == null ? null : files.findById(clip.fileId()).orElse(null);
        return Dtos.ClipDto.from(clip, file);
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        OutputStream nonClosing = zip;
        nonClosing.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
