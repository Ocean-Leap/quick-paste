package com.quickpaste.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

@MappedEntity("api_tokens")
public record ApiToken(
        @Id String id,
        String userId,
        String name,
        String tokenHash,
        String prefix,
        String scope,
        Instant createdAt,
        @Nullable Instant lastUsedAt,
        @Nullable Instant expiresAt,
        @Nullable Instant revokedAt) {

    public static final String SCOPE_READ = "read";
    public static final String SCOPE_WRITE = "write";
}
