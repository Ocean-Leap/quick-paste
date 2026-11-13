package com.quickpaste.sink;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/** Client for Steward Mind ingest (SPEC §5.14). */
public interface StewardIngestClient {

    @Serdeable
    record IngestBody(
            String kind,
            @Nullable String text,
            @Nullable String url,
            @Nullable List<String> tags,
            String idempotency_key) {
    }

    @Serdeable
    record IngestResponse(
            @Nullable Long item_id,
            @Nullable Boolean created,
            @Nullable Boolean deduped,
            @Nullable String kind) {
    }

    sealed interface Result {
        record Ok(IngestResponse body) implements Result {
        }

        record Retryable(int status, @Nullable Integer retryAfterSeconds, String message) implements Result {
        }

        record Fatal(int status, String message) implements Result {
        }
    }

    Result ingest(String baseUrl, String bearerToken, IngestBody body);
}
