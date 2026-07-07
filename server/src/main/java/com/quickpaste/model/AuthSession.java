package com.quickpaste.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

@MappedEntity("sessions")
public record AuthSession(
        @Id String id,
        String userId,
        Instant createdAt,
        Instant expiresAt,
        String userAgent,
        @Nullable Instant revokedAt) {
}
