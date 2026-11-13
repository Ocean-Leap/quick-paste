package com.quickpaste.service;

import com.quickpaste.config.QuickPasteConfig;
import com.quickpaste.model.Channel;
import com.quickpaste.model.ChannelSink;
import com.quickpaste.model.Clip;
import com.quickpaste.model.ClipDelivery;
import com.quickpaste.repository.ChannelRepository;
import com.quickpaste.repository.ChannelSinkRepository;
import com.quickpaste.repository.ClipDeliveryRepository;
import com.quickpaste.repository.ClipRepository;
import com.quickpaste.security.SessionService;
import com.quickpaste.sink.StewardIngestClient;
import com.quickpaste.sink.StewardMindMapper;
import com.quickpaste.util.CryptoUtil;
import com.quickpaste.util.HostSafety;
import com.quickpaste.util.TagsJson;
import com.quickpaste.ws.WsRegistry;
import com.quickpaste.error.ApiException;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Enqueues and retries Steward Mind deliveries for external channels (SPEC FR-64).
 * Never logs tokens or clip payloads. Create path only inserts a pending row;
 * HTTP runs on the blocking executor so POST /clips stays fast.
 */
@Singleton
public class SinkDeliveryService {

    private static final Logger LOG = LoggerFactory.getLogger(SinkDeliveryService.class);
    /** Lease so a concurrent poll won't re-claim the same pending row mid-flight. */
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(2);

    private final QuickPasteConfig config;
    private final ClipDeliveryRepository deliveries;
    private final ChannelSinkRepository sinks;
    private final ChannelRepository channels;
    private final ClipRepository clips;
    private final StewardIngestClient steward;
    private final WsRegistry ws;
    private final Provider<ClipService> clipService;
    private final ExecutorService executor;
    private final byte[] aesKey;
    /** Per-clip monitors (single-instance process — NFR-7). */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public SinkDeliveryService(QuickPasteConfig config, ClipDeliveryRepository deliveries,
                               ChannelSinkRepository sinks, ChannelRepository channels,
                               ClipRepository clips, StewardIngestClient steward, WsRegistry ws,
                               Provider<ClipService> clipService, SessionService sessions,
                               @Named(TaskExecutors.BLOCKING) ExecutorService executor) {
        this.config = config;
        this.deliveries = deliveries;
        this.sinks = sinks;
        this.channels = channels;
        this.clips = clips;
        this.steward = steward;
        this.ws = ws;
        this.clipService = clipService;
        this.executor = executor;
        this.aesKey = sessions.aesKey();
    }

    /**
     * Inserts a pending delivery and schedules an async attempt. The clip create
     * response always sees {@code pending} (FR-64).
     */
    public void enqueue(Clip clip, @Nullable String mindKind) {
        Instant now = Instant.now();
        String kind;
        try {
            kind = StewardMindMapper.map(mindKind, clip.text(), null, clip.id()).kind();
        } catch (IllegalArgumentException e) {
            // Terminal — never polled (findDue is pending-only).
            ClipDelivery failed = new ClipDelivery(clip.id(), clip.channelId(), ClipDelivery.STATUS_FAILED,
                    null, null, config.getSinkMaxAttempts(), now, truncate(e.getMessage()), now);
            deliveries.save(failed);
            return;
        }
        ClipDelivery row = new ClipDelivery(clip.id(), clip.channelId(), ClipDelivery.STATUS_PENDING,
                kind, null, 0, now, null, now);
        deliveries.save(row);
        String kindOverride = mindKind;
        executor.execute(() -> {
            try {
                deliverClaimed(clip.id(), kindOverride);
            } catch (Exception e) {
                LOG.warn("sink_delivery async_failed clipId={} err={}",
                        clip.id(), e.getClass().getSimpleName());
            }
        });
    }

    @Scheduled(fixedDelay = "${quickpaste.sink-delivery-interval-sec}s", initialDelay = "20s")
    public void pollDue() {
        Instant now = Instant.now();
        List<ClipDelivery> due = deliveries.findDue(now, config.getSinkMaxAttempts(), 25);
        for (ClipDelivery row : due) {
            executor.execute(() -> {
                try {
                    deliverClaimed(row.clipId(), null);
                } catch (Exception e) {
                    LOG.warn("sink_delivery poll_failed clipId={} err={}",
                            row.clipId(), e.getClass().getSimpleName());
                }
            });
        }
    }

    public Optional<ClipDelivery> find(String clipId) {
        return deliveries.findByClipId(clipId);
    }

    public List<ClipDelivery> findForClips(List<String> clipIds) {
        if (clipIds == null || clipIds.isEmpty()) {
            return List.of();
        }
        return deliveries.findByClipIds(clipIds);
    }

    private void deliverClaimed(String clipId, @Nullable String mindKindOverride) {
        Object lock = locks.computeIfAbsent(clipId, id -> new Object());
        synchronized (lock) {
            Instant now = Instant.now();
            Optional<ClipDelivery> current = deliveries.findByClipId(clipId);
            if (current.isEmpty()) {
                return;
            }
            ClipDelivery row = current.get();
            if (!ClipDelivery.STATUS_PENDING.equals(row.status())) {
                return;
            }
            if (row.attempts() >= config.getSinkMaxAttempts()) {
                markTerminal(row, "Max delivery attempts reached.", now);
                return;
            }
            if (row.nextAttemptAt().isAfter(now)) {
                return; // still leased / scheduled
            }
            Instant leaseUntil = now.plus(CLAIM_LEASE);
            if (deliveries.claimDue(clipId, now, leaseUntil) == 0) {
                return; // lost race
            }
            // Reload after claim so attempts/kind are current.
            row = deliveries.findByClipId(clipId).orElse(row);
            deliverOne(row, mindKindOverride);
        }
    }

    private void deliverOne(ClipDelivery row, @Nullable String mindKindOverride) {
        Instant now = Instant.now();

        Optional<Channel> channel = channels.findById(row.channelId());
        if (channel.isEmpty() || !Channel.TYPE_EXTERNAL.equals(channel.get().type())) {
            markTerminal(row, "Channel is not an external sink.", now);
            return;
        }
        Optional<ChannelSink> sinkOpt = sinks.findByChannelId(row.channelId());
        Optional<Clip> clipOpt = clips.findByIdAndChannelId(row.clipId(), row.channelId());
        if (sinkOpt.isEmpty() || clipOpt.isEmpty()) {
            markTerminal(row, "Missing clip or sink config.", now);
            return;
        }
        ChannelSink sink = sinkOpt.get();
        Clip clip = clipOpt.get();
        if (clip.text() == null || clip.text().isEmpty()) {
            markTerminal(row, "External sinks only accept text clips.", now);
            return;
        }

        List<String> tags = TagsJson.read(sink.defaultTags());
        StewardMindMapper.Payload payload;
        try {
            payload = StewardMindMapper.map(
                    mindKindOverride != null ? mindKindOverride : row.kind(),
                    clip.text(), tags, clip.id());
        } catch (IllegalArgumentException e) {
            markTerminal(row, e.getMessage(), now);
            return;
        }

        String token;
        try {
            token = CryptoUtil.decryptAesGcm(aesKey, sink.tokenCiphertext());
        } catch (RuntimeException e) {
            markTerminal(row, "Unable to decrypt sink token.", now);
            return;
        }

        StewardIngestClient.IngestBody body = new StewardIngestClient.IngestBody(
                payload.kind(), payload.text(), payload.url(), payload.tags(), payload.idempotencyKey());
        // Re-check before every outbound call (defense in depth if DB row was tampered).
        try {
            HostSafety.requirePublicHttpsBase(sink.baseUrl());
        } catch (ApiException e) {
            markTerminal(row, e.getMessage(), now);
            return;
        }
        StewardIngestClient.Result result = steward.ingest(sink.baseUrl(), token, body);
        now = Instant.now();
        int attempts = row.attempts() + 1;

        if (result instanceof StewardIngestClient.Result.Ok ok) {
            boolean deduped = Boolean.TRUE.equals(ok.body().deduped());
            String status = deduped ? ClipDelivery.STATUS_DEDUPED : ClipDelivery.STATUS_SENT;
            String externalId = ok.body().item_id() == null ? null : String.valueOf(ok.body().item_id());
            ClipDelivery updated = new ClipDelivery(row.clipId(), row.channelId(), status,
                    payload.kind(), externalId, attempts, now, null, now);
            deliveries.update(updated);
            LOG.info("sink_delivery {} channelId={} clipId={} attempts={}",
                    status, row.channelId(), row.clipId(), attempts);
            broadcast(clip);
        } else if (result instanceof StewardIngestClient.Result.Retryable retry) {
            if (attempts >= config.getSinkMaxAttempts()) {
                ClipDelivery failed = new ClipDelivery(row.clipId(), row.channelId(),
                        ClipDelivery.STATUS_FAILED, payload.kind(), null, attempts, now,
                        truncate(retry.message()), now);
                deliveries.update(failed);
                LOG.warn("sink_delivery failed channelId={} clipId={} attempts={}",
                        row.channelId(), row.clipId(), attempts);
                broadcast(clip);
                return;
            }
            int delaySec = retry.retryAfterSeconds() != null && retry.retryAfterSeconds() > 0
                    ? retry.retryAfterSeconds()
                    : backoffSeconds(attempts);
            Instant next = now.plus(Duration.ofSeconds(delaySec));
            ClipDelivery updated = new ClipDelivery(row.clipId(), row.channelId(),
                    ClipDelivery.STATUS_PENDING, payload.kind(), null, attempts, next,
                    truncate(retry.message()), now);
            deliveries.update(updated);
            LOG.info("sink_delivery retry channelId={} clipId={} attempts={} nextInSec={}",
                    row.channelId(), row.clipId(), attempts, delaySec);
        } else if (result instanceof StewardIngestClient.Result.Fatal fatal) {
            // Terminal — excluded from findDue (pending-only).
            ClipDelivery failed = new ClipDelivery(row.clipId(), row.channelId(),
                    ClipDelivery.STATUS_FAILED, payload.kind(), null, Math.max(attempts, config.getSinkMaxAttempts()),
                    now, truncate(fatal.message()), now);
            deliveries.update(failed);
            LOG.warn("sink_delivery failed channelId={} clipId={} attempts={}",
                    row.channelId(), row.clipId(), attempts);
            broadcast(clip);
        }
    }

    private void markTerminal(ClipDelivery base, String message, Instant now) {
        ClipDelivery updated = new ClipDelivery(base.clipId(), base.channelId(), ClipDelivery.STATUS_FAILED,
                base.kind(), base.externalItemId(), Math.max(base.attempts(), config.getSinkMaxAttempts()),
                now, truncate(message), now);
        if (deliveries.findByClipId(base.clipId()).isPresent()) {
            deliveries.update(updated);
        } else {
            deliveries.save(updated);
        }
        LOG.warn("sink_delivery failed channelId={} clipId={} attempts={}",
                base.channelId(), base.clipId(), updated.attempts());
        clips.findByIdAndChannelId(base.clipId(), base.channelId()).ifPresent(this::broadcast);
    }

    private void broadcast(Clip clip) {
        try {
            ws.clipCreated(clipService.get().toDto(clip));
        } catch (Exception e) {
            LOG.warn("sink_delivery broadcast_failed clipId={}", clip.id());
        }
    }

    private static int backoffSeconds(int attempts) {
        long delay = 15L * (1L << Math.min(Math.max(attempts - 1, 0), 6));
        return (int) Math.min(delay, 900L);
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 512 ? message : message.substring(0, 512);
    }
}
