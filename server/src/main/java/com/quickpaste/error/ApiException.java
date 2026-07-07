package com.quickpaste.error;

import java.util.Map;

/**
 * Application error carrying the SPEC §8.3 envelope fields. Thrown from services/filters,
 * rendered by {@link ApiExceptionHandler}.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final transient Map<String, Object> details;

    public ApiException(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ApiException unauthenticated() {
        return new ApiException(401, "unauthenticated", "Authentication required.", Map.of());
    }

    public static ApiException notAuthorized(String message) {
        return new ApiException(403, "not_authorized", message, Map.of());
    }

    public static ApiException notFound() {
        return new ApiException(404, "not_found", "Not found.", Map.of());
    }

    public static ApiException validation(String message) {
        return new ApiException(422, "validation_failed", message, Map.of());
    }

    public static ApiException payloadTooLarge(String message) {
        return new ApiException(413, "payload_too_large", message, Map.of());
    }

    public static ApiException quotaExceeded(String message) {
        return new ApiException(409, "quota_exceeded", message, Map.of());
    }

    public static ApiException conflict(String message) {
        return new ApiException(409, "conflict", message, Map.of());
    }

    public static ApiException rateLimited(int retryAfterSeconds) {
        return new ApiException(429, "rate_limited", "Too many requests.",
                Map.of("retryAfterSeconds", retryAfterSeconds));
    }
}
