package com.quickpaste.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/** Per-clip outbound delivery status for external sinks (SPEC §5.14 / §8.1). */
@MappedEntity("clip_deliveries")
public record ClipDelivery(
        @Id String clipId,
        String channelId,
        String status,
        @Nullable String kind,
        @Nullable String externalItemId,
        int attempts,
        Instant nextAttemptAt,
        @Nullable String lastError,
        Instant updatedAt) {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_DEDUPED = "deduped";
    public static final String STATUS_FAILED = "failed";
}
