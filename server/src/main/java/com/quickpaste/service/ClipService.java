package com.quickpaste.service;

import com.quickpaste.config.QuickPasteConfig;
import com.quickpaste.error.ApiException;
import com.quickpaste.model.Clip;
import com.quickpaste.model.StoredFile;
import com.quickpaste.model.User;
import com.quickpaste.model.dto.Dtos;
import com.quickpaste.model.dto.Requests;
import com.quickpaste.repository.ClipRepository;
import com.quickpaste.repository.StoredFileRepository;
import com.quickpaste.util.Hashing;
import com.quickpaste.util.Ids;
import com.quickpaste.ws.WsRegistry;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Clip lifecycle (SPEC §5.3, §5.6, §5.7): create with dedup + idempotency, list, delete, clear. */
@Singleton
public class ClipService {

    private final QuickPasteConfig config;
    private final ClipRepository clips;
    private final StoredFileRepository files;
    private final ChannelService channelService;
    private final FileService fileService;
    private final IdempotencyCache idempotency;
    private final WsRegistry ws;
    private final PushNotifier pushNotifier;

    public ClipService(QuickPasteConfig config, ClipRepository clips, StoredFileRepository files,
                       ChannelService channelService, FileService fileService,
                       IdempotencyCache idempotency, WsRegistry ws, PushNotifier pushNotifier) {
        this.config = config;
        this.clips = clips;
        this.files = files;
        this.channelService = channelService;
        this.fileService = fileService;
        this.idempotency = idempotency;
        this.ws = ws;
        this.pushNotifier = pushNotifier;
    }

    /** {@code created} distinguishes a fresh clip (201) from a dedup no-op (200, FR-18). */
    public record CreateResult(Dtos.ClipDto clip, boolean created) {
    }

    public CreateResult create(User user, String channelId, Requests.CreateClipRequest request) {
        channelService.requireActiveMember(user.id(), channelId);

        String origin = request.origin() == null ? "" : request.origin().trim();
        if (origin.isEmpty() || origin.length() > 64) {
            throw ApiException.validation("origin (device label) is required, 1-64 characters.");
        }

        String idempotencyKey = request.idempotencyKey();
        String cacheKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? null
                : user.id() + ":" + channelId + ":" + idempotencyKey;
        if (cacheKey != null) {
            Optional<IdempotencyCache.CachedCreate> replay =
                    idempotency.get(cacheKey, config.getIdempotencyTtlSec());
            if (replay.isPresent()) {
                // FR-41: replay the original response, rebuilt from the row. If the clip
                // has since been deleted or expired, fall through and create normally.
                Optional<Clip> original = clips.findByIdAndChannelId(replay.get().clipId(), channelId);
                if (original.isPresent()) {
                    return new CreateResult(toDto(original.get()), replay.get().created());
                }
            }
        }

        Instant now = Instant.now();
        String type = request.type();
        String text = null;
        StoredFile file = null;
        String contentHash;
        Instant expiresAt;
        if (Clip.TYPE_TEXT.equals(type)) {
            text = request.text();
            if (text == null || text.isEmpty()) {
                throw ApiException.validation("text is required for type=text.");
            }
            int bytes = text.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > config.getMaxTextKb() * 1024L) {
                throw ApiException.payloadTooLarge(
                        "Text exceeds " + config.getMaxTextKb() + " KB (QP_MAX_TEXT_KB); send it as a file.");
            }
            contentHash = Hashing.sha256Hex(text);
            expiresAt = now.plus(Duration.ofHours(config.getClipTtlHours()));
        } else if (Clip.TYPE_FILE.equals(type)) {
            if (request.fileId() == null || request.fileId().isBlank()) {
                throw ApiException.validation("fileId is required for type=file.");
            }
            file = files.findByIdAndChannelId(request.fileId(), channelId)
                    .orElseThrow(() -> ApiException.validation("Unknown fileId for this channel."));
            contentHash = file.checksum();
            expiresAt = file.expiresAt(); // file clips live as long as their blob (FR-35)
        } else {
            throw ApiException.validation("type must be 'text' or 'file'.");
        }

        // FR-42/FR-18: same (channel, user, origin, hash) within the window is a no-op.
        // The user id is part of the key — another member's identical clip must never
        // suppress this user's push (device labels are not unique across users).
        Instant dedupCutoff = now.minus(Duration.ofSeconds(config.getDedupWindowSec()));
        Optional<Clip> duplicate =
                clips.findRecentDuplicate(channelId, user.id(), origin, contentHash, dedupCutoff);
        if (duplicate.isPresent()) {
            CreateResult result = new CreateResult(toDto(duplicate.get()), false);
            if (cacheKey != null) {
                idempotency.put(cacheKey, duplicate.get().id(), false);
            }
            return result;
        }

        Clip clip = new Clip(Ids.newUlid(), channelId, user.id(), origin, type, text,
                file == null ? null : file.id(), contentHash, false, null, null, now, expiresAt);
        clips.save(clip);
        Dtos.ClipDto dto = Dtos.ClipDto.from(clip, file);
        ws.clipCreated(dto);
        pushNotifier.clipCreated(dto);
        if (cacheKey != null) {
            idempotency.put(cacheKey, clip.id(), true);
        }
        return new CreateResult(dto, true);
    }

    public Optional<Dtos.ClipDto> latest(User user, String channelId) {
        channelService.requireActiveMember(user.id(), channelId);
        return clips.findLatest(channelId, Instant.now()).map(this::toDto);
    }

    public Dtos.ClipListResponse list(User user, String channelId, @Nullable String afterId,
                                      @Nullable String beforeId, @Nullable Integer limit,
                                      @Nullable String type) {
        channelService.requireActiveMember(user.id(), channelId);
        if (type != null && !Clip.TYPE_TEXT.equals(type) && !Clip.TYPE_FILE.equals(type)) {
            throw ApiException.validation("type filter must be 'text' or 'file'.");
        }
        int effectiveLimit = limit == null ? 50 : Math.max(1, Math.min(200, limit));
        Instant now = Instant.now();
        List<Clip> rows = afterId != null && !afterId.isBlank()
                ? clips.listAfter(channelId, now, afterId, type, effectiveLimit)
                : clips.listBefore(channelId, now, blankToNull(beforeId), type, effectiveLimit);
        List<Dtos.ClipDto> items = new ArrayList<>(rows.size());
        for (Clip row : rows) {
            items.add(toDto(row));
        }
        String nextCursor = rows.size() == effectiveLimit ? rows.get(rows.size() - 1).id() : null;
        return new Dtos.ClipListResponse(items, nextCursor);
    }

    public void delete(User user, String channelId, String clipId) {
        channelService.requireActiveMember(user.id(), channelId);
        Clip clip = clips.findByIdAndChannelId(clipId, channelId).orElseThrow(ApiException::notFound);
        clips.deleteById(clip.id());
        if (clip.fileId() != null && clips.countByChannelIdAndFileId(channelId, clip.fileId()) == 0) {
            // FR-34: last reference in this channel gone — remove blob + row.
            files.findById(clip.fileId()).ifPresent(fileService::deleteBlobAndRow);
        }
        ws.clipDeleted(channelId, clip.id());
    }

    public void clear(User user, String channelId) {
        channelService.requireActiveMember(user.id(), channelId);
        clips.deleteByChannel(channelId);
        for (StoredFile file : files.findByChannelId(channelId)) {
            fileService.deleteBlobAndRow(file);
        }
        ws.clipsCleared(channelId);
    }

    public Dtos.ClipDto toDto(Clip clip) {
        StoredFile file = clip.fileId() == null ? null : files.findById(clip.fileId()).orElse(null);
        return Dtos.ClipDto.from(clip, file);
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
