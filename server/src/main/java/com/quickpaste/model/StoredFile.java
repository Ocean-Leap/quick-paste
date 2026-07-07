package com.quickpaste.model;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/** File metadata; blob bytes live at {@code storagePath} under QP_STORAGE_DIR/{channelId}/. */
@MappedEntity("files")
public record StoredFile(
        @Id String id,
        String channelId,
        String createdBy,
        String name,
        String mime,
        long size,
        String storagePath,
        String checksum,
        Instant createdAt,
        Instant expiresAt) {
}
