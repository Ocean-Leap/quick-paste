package com.quickpaste.util;

import io.micronaut.core.annotation.Nullable;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/** DTO timestamps are ISO-8601 strings; entities keep {@link Instant}. */
public final class Iso {

    private Iso() {
    }

    @Nullable
    public static String format(@Nullable Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant);
    }
}
