package com.quickpaste.error;

import io.micronaut.serde.annotation.Serdeable;

import java.util.Map;

/** Error envelope (SPEC §8.3): {"error": {"code", "message", "details"}}. */
@Serdeable
public record ErrorResponse(ErrorInfo error) {

    @Serdeable
    public record ErrorInfo(String code, String message, Map<String, Object> details) {
    }

    public static ErrorResponse of(String code, String message, Map<String, Object> details) {
        return new ErrorResponse(new ErrorInfo(code, message, details));
    }
}
