package com.quickpaste.service;

import jakarta.inject.Singleton;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user monitors used to serialize quota check-then-commit sections
 * (FR-43): without them, parallel uploads all pass the quota check against
 * the same stale usage figure. Entries are never removed — the map is
 * bounded by the user count (NFR-7, < ~20).
 */
@Singleton
public class UserLocks {

    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public Object lockFor(String userId) {
        return locks.computeIfAbsent(userId, k -> new Object());
    }
}
