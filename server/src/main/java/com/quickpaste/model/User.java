package com.quickpaste.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

@MappedEntity("users")
public record User(
        @Id String id,
        String email,
        @Nullable String googleSub,
        String displayName,
        boolean isAdmin,
        String status,
        Instant createdAt,
        @Nullable Instant lastLoginAt) {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";
}
