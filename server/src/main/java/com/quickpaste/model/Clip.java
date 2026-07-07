package com.quickpaste.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/**
 * A clipboard item. {@code encrypted}/{@code encAlgo}/{@code iv} are the reserved
 * v2 E2E envelope (SPEC §9.9) — always {@code false}/{@code null} in v1.
 */
@MappedEntity("clips")
public record Clip(
        @Id String id,
        String channelId,
        String originUserId,
        String originDevice,
        String type,
        @Nullable String text,
        @Nullable String fileId,
        String contentHash,
        boolean encrypted,
        @Nullable String encAlgo,
        @Nullable byte[] iv,
        Instant createdAt,
        Instant expiresAt) {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_FILE = "file";
}
