package com.quickpaste.sink;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * HTTP implementation of Steward Mind ingest. Uses the JDK client with redirects
 * disabled (SSRF defense). Logs status metadata only — never tokens or payloads.
 */
@Singleton
public class StewardHttpIngestClient implements StewardIngestClient {

    private static final Logger LOG = LoggerFactory.getLogger(StewardHttpIngestClient.class);

    private final HttpClient http;
    private final ObjectMapper mapper;

    public StewardHttpIngestClient(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public Result ingest(String baseUrl, String bearerToken, IngestBody body) {
        URI target = URI.create(normalizeBase(baseUrl) + "/api/mind/ingest");
        try {
            byte[] json = mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + bearerToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                IngestResponse parsed = mapper.readValue(response.body(), IngestResponse.class);
                if (parsed == null) {
                    return new Result.Fatal(status, "Empty Steward response body.");
                }
                LOG.info("steward_ingest ok status={} created={} deduped={}",
                        status, parsed.created(), parsed.deduped());
                return new Result.Ok(parsed);
            }
            String message = truncate("Steward HTTP " + status);
            LOG.warn("steward_ingest failed status={}", status);
            if (status == 429) {
                Integer retryAfter = parseRetryAfter(response);
                return new Result.Retryable(status, retryAfter, message);
            }
            if (status >= 500 || status == 0) {
                return new Result.Retryable(status, null, message);
            }
            return new Result.Fatal(status, message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result.Retryable(0, null, "Interrupted");
        } catch (Exception e) {
            String message = truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            LOG.warn("steward_ingest transport_error message={}", message);
            return new Result.Retryable(0, null, message);
        }
    }

    public static String normalizeBase(String baseUrl) {
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    @Nullable
    private static Integer parseRetryAfter(HttpResponse<?> response) {
        Optional<String> header = response.headers().firstValue("Retry-After");
        if (header.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(header.get().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String truncate(String message) {
        return message.length() <= 400 ? message : message.substring(0, 400);
    }
}
