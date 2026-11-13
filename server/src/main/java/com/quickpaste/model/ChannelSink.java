package com.quickpaste.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/** Outbound sink config for an external channel (SPEC §5.14 / §8.1). */
@MappedEntity("channel_sinks")
public record ChannelSink(
        @Id String channelId,
        String provider,
        String baseUrl,
        String tokenCiphertext,
        String tokenPrefix,
        @Nullable String defaultTags,
        Instant createdAt,
        Instant updatedAt) {

    public static final String PROVIDER_STEWARD = "steward";
}
