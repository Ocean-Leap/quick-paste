package com.quickpaste.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

@MappedEntity("export_jobs")
public record ExportJob(
        @Id String id,
        String userId,
        String status,
        @Nullable String storagePath,
        Instant createdAt,
        @Nullable Instant expiresAt) {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";
}
