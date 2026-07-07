package com.quickpaste.security;

import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fixed-window rate limiter (FR-44). Single-instance by design (NFR-7);
 * entries are evicted by the sweep.
 */
@Singleton
public class RateLimiter {

    private static final class Counter {
        long minute;
        int count;
    }

    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public boolean tryAcquire(String key, int limitPerMinute) {
        long minute = Instant.now().getEpochSecond() / 60;
        Counter counter = counters.computeIfAbsent(key, k -> new Counter());
        synchronized (counter) {
            if (counter.minute != minute) {
                counter.minute = minute;
                counter.count = 0;
            }
            counter.count++;
            return counter.count <= limitPerMinute;
        }
    }

    public int retryAfterSeconds() {
        return (int) (60 - Instant.now().getEpochSecond() % 60);
    }

    public void evictStale() {
        long minute = Instant.now().getEpochSecond() / 60;
        counters.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().minute < minute - 2;
            }
        });
    }
}
