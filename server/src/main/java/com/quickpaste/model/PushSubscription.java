package com.quickpaste.model;

import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;

/** A Web Push subscription (FR-38); one row per browser/device registration. */
@MappedEntity("push_subscriptions")
public record PushSubscription(
        @Id String id,
        String userId,
        String endpoint,
        String p256dh,
        String auth,
        String device,
        Instant createdAt) {
}
