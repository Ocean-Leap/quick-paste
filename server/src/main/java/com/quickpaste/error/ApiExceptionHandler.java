package com.quickpaste.error;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;

@Produces
@Singleton
@Requires(classes = ApiException.class)
public class ApiExceptionHandler implements ExceptionHandler<ApiException, HttpResponse<ErrorResponse>> {

    @Override
    public HttpResponse<ErrorResponse> handle(HttpRequest request, ApiException e) {
        MutableHttpResponse<ErrorResponse> response = HttpResponse
                .status(HttpStatus.valueOf(e.status()))
                .body(ErrorResponse.of(e.code(), e.getMessage(), e.details()));
        Object retryAfter = e.details().get("retryAfterSeconds");
        if (retryAfter != null) {
            response.header("Retry-After", String.valueOf(retryAfter));
        }
        return response;
    }
}
