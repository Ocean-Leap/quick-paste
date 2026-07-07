package com.quickpaste.model;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

@MappedEntity("channel_members")
public record ChannelMember(
        @Id String id,
        String channelId,
        @Nullable String userId,
        @Nullable String invitedEmail,
        String role,
        String status,
        @Nullable Instant joinedAt) {

    public static final String ROLE_OWNER = "owner";
    public static final String ROLE_MEMBER = "member";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ACTIVE = "active";
}
