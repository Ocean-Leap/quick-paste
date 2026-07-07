package com.quickpaste;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The login rate limiter must key on a trustworthy client IP. With
 * QP_TRUSTED_PROXY_COUNT=1, a spoofed left-prepended X-Forwarded-For entry must
 * not create a fresh rate-limit bucket, so brute force still trips the limit.
 */
@MicronautTest(transactional = false)
class ProxyTrustTest extends DbTestBase {

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> props = super.getProperties();
        props.put("quickpaste.auth-mode", "local");
        props.put("quickpaste.local-password", "pw");
        props.put("quickpaste.trusted-proxy-count", "1");
        props.put("quickpaste.login-rate-limit-per-min", "5");
        return props;
    }

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void spoofedLeftmostXffDoesNotEvadeLoginRateLimit() {
        // One real proxy hop: the rightmost XFF entry is the trusted value. The
        // attacker varies only the leftmost (spoofed) entry, which must be ignored.
        HttpStatus last = null;
        for (int i = 0; i < 12; i++) {
            try {
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/auth/login-local", Map.of("password", "wrong-" + i))
                                .header("X-Forwarded-For", "9.9.9." + i + ", 203.0.113.5"));
                last = HttpStatus.OK;
            } catch (HttpClientResponseException e) {
                last = e.getStatus();
            }
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, last,
                "varying the spoofable leftmost XFF must not evade the per-IP login limit");
    }

    @Test
    void distinctTrustedClientIpsGetSeparateBuckets() {
        // Different real clients (distinct rightmost/trusted entry) keep independent
        // buckets, so one abuser can't lock everyone out.
        for (int i = 0; i < 5; i++) {
            expectAuthFailure("198.51.100.1");
        }
        // A 6th attempt from that client is limited...
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, statusFor("198.51.100.1", "x"));
        // ...but a different trusted client still gets through to an auth check.
        assertEquals(HttpStatus.UNAUTHORIZED, statusFor("198.51.100.2", "x"));
    }

    private void expectAuthFailure(String trustedIp) {
        assertEquals(HttpStatus.UNAUTHORIZED, statusFor(trustedIp, "wrong"));
    }

    private HttpStatus statusFor(String trustedIp, String password) {
        try {
            client.toBlocking().exchange(
                    HttpRequest.POST("/api/auth/login-local", Map.of("password", password))
                            .header("X-Forwarded-For", "10.0.0.9, " + trustedIp));
            return HttpStatus.OK;
        } catch (HttpClientResponseException e) {
            return e.getStatus();
        }
    }
}
