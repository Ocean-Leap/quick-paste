package com.quickpaste.model.dto;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

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
            @Nullable String idempotencyKey) {
    }

    @Serdeable
    public record CreateChannelRequest(@Nullable String name) {
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
