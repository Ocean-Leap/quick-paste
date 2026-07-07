package com.quickpaste.model;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/** In-flight resumable upload (SPEC §8.6). The id becomes the fileId when finalized. */
@MappedEntity("upload_sessions")
public record UploadSession(
        @Id String id,
        String channelId,
        String createdBy,
        String name,
        String mime,
        long declaredSize,
        Instant createdAt,
        Instant updatedAt) {
}
