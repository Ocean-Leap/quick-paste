package com.quickpaste.service;

import jakarta.inject.Singleton;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replays the original create-clip response for a repeated idempotency key (FR-41).
 * Entries hold only the clip id + created flag — never clip content — so the cache
 * cannot pin payloads in heap; the DTO is rebuilt from the row on replay. In-memory
 * is sufficient for the single-instance design (NFR-7); entries are evicted by the
 * sweep after QP_IDEMPOTENCY_TTL_SEC.
 */
@Singleton
public class IdempotencyCache {

    public record CachedCreate(String clipId, boolean created, Instant storedAt) {
    }

    private final ConcurrentHashMap<String, CachedCreate> entries = new ConcurrentHashMap<>();

    public Optional<CachedCreate> get(String key, int ttlSeconds) {
        CachedCreate cached = entries.get(key);
        if (cached == null) {
            return Optional.empty();
        }
        if (cached.storedAt().plus(Duration.ofSeconds(ttlSeconds)).isBefore(Instant.now())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(cached);
    }

    public void put(String key, String clipId, boolean created) {
        entries.put(key, new CachedCreate(clipId, created, Instant.now()));
    }

    public void evictOlderThan(int ttlSeconds) {
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(ttlSeconds));
        entries.entrySet().removeIf(e -> e.getValue().storedAt().isBefore(cutoff));
    }
}
