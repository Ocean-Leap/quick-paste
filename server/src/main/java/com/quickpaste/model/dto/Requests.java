package com.quickpaste.model.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/** Request bodies (SPEC §8.2). Validation happens in the services. */
public final class Requests {

    private Requests() {
    }

    @Serdeable
    public record CreateClipRequest(
            @Nullable String type,
            @Nullable String text,
            @Nullable String fileId,
            @Nullable String origin,
            @Nullable String idempotencyKey,
            @Nullable String mindKind) {
    }

    @Serdeable
    public record CreateChannelRequest(
            @Nullable String name,
            @Nullable String type,
            @Nullable String provider,
            @Nullable String baseUrl,
            @Nullable String token,
            @Nullable List<String> defaultTags) {
    }

    @Serdeable
    public record PatchSinkRequest(
            @Nullable String token,
            @Nullable String baseUrl,
            @Nullable List<String> defaultTags) {
    }

    @Serdeable
    public record InviteRequest(@Nullable String email) {
    }

    @Serdeable
    public record CreateTokenRequest(@Nullable String name, @Nullable String scope) {
    }

    @Serdeable
    public record PatchUserStatusRequest(@Nullable String status) {
    }

    @Serdeable
    public record LocalLoginRequest(@Nullable String password) {
    }

    @Serdeable
    public record PushSubscribeRequest(
            @Nullable String endpoint,
            @Nullable Keys keys,
            @Nullable String device) {

        @Serdeable
        public record Keys(@Nullable String p256dh, @Nullable String auth) {
        }
    }
}
