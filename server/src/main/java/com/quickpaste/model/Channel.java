package com.quickpaste.model;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

@MappedEntity("channels")
public record Channel(
        @Id String id,
        String name,
        String type,
        String createdBy,
        Instant createdAt) {

    public static final String TYPE_PRIVATE = "private";
    public static final String TYPE_SHARED = "shared";
    public static final String TYPE_EXTERNAL = "external";
}
