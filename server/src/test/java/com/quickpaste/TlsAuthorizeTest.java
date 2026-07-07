package com.quickpaste;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Caddy on-demand TLS gate: only private/loopback/.local SNIs may be issued. */
@MicronautTest(transactional = false)
class TlsAuthorizeTest extends DbTestBase {

    @Inject
    @Client("/")
    HttpClient client;

    private int status(String domain) {
        try {
            return client.toBlocking().exchange(
                    HttpRequest.GET("/internal/tls-authorize?domain=" + domain)).code();
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    @Test
    void approvesLanNamesAndRejectsPublicOnes() {
        assertEquals(HttpStatus.OK.getCode(), status("192.168.1.50"));
        assertEquals(HttpStatus.OK.getCode(), status("10.0.0.4"));
        assertEquals(HttpStatus.OK.getCode(), status("172.16.5.5"));
        assertEquals(HttpStatus.OK.getCode(), status("mybox.local"));
        assertEquals(HttpStatus.OK.getCode(), status("localhost"));

        assertEquals(HttpStatus.FORBIDDEN.getCode(), status("evil.example.com"));
        assertEquals(HttpStatus.FORBIDDEN.getCode(), status("8.8.8.8"));
        assertEquals(HttpStatus.FORBIDDEN.getCode(), status("172.32.0.1")); // just outside 172.16/12
        assertEquals(HttpStatus.FORBIDDEN.getCode(), status(""));
    }
}
