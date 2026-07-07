package com.quickpaste;

import com.quickpaste.model.dto.Dtos;
import com.quickpaste.security.SessionService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@MicronautTest(transactional = false)
class AuthFilterTest extends DbTestBase {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    TestData testData;

    @Inject
    SessionService sessionService;

    @Inject
    com.quickpaste.repository.UserRepository users;

    @Test
    void authModeIsGoogleByDefaultAndUnauthenticated() {
        com.quickpaste.model.dto.Dtos.AuthModeDto mode = client.toBlocking().retrieve(
                HttpRequest.GET("/api/auth/mode"),
                com.quickpaste.model.dto.Dtos.AuthModeDto.class);
        assertEquals("google", mode.mode());
    }

    @Test
    void rejectsMissingAuth() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.GET("/api/auth/me")));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }

    @Test
    void rejectsGarbageBearerToken() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.GET("/api/auth/me").header("Authorization", "Bearer qp_dead.beefbeef")));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }

    @Test
    void acceptsValidBearerToken() {
        TestData.Fixture fx = testData.user("filter-token@test.local", false);
        Dtos.MeResponse me = client.toBlocking().retrieve(
                HttpRequest.GET("/api/auth/me").header("Authorization", "Bearer " + fx.bearer()),
                Dtos.MeResponse.class);
        assertEquals("filter-token@test.local", me.user().email());
        assertEquals(1, me.channels().size());
        assertEquals("private", me.channels().get(0).type());
    }

    @Test
    void readScopedTokenCannotMutate() {
        TestData.Fixture fx = testData.user("filter-readonly@test.local", false);
        String readOnly = testData.readOnlyToken(fx.user());
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.POST("/api/channels", Map.of("name", "nope"))
                                .header("Authorization", "Bearer " + readOnly)));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
    }

    @Test
    void sessionCookieRequiresCsrfTokenForMutations() {
        TestData.Fixture fx = testData.user("filter-csrf@test.local", false);
        SessionService.NewSession session = sessionService.create(fx.user(), "junit");
        Cookie sessionCookie = Cookie.of(SessionService.COOKIE_SESSION, session.cookieValue());
        Cookie csrfCookie = Cookie.of(SessionService.COOKIE_CSRF, session.csrfToken());

        // Reads work without CSRF.
        HttpResponse<Dtos.MeResponse> me = client.toBlocking().exchange(
                HttpRequest.GET("/api/auth/me").cookie(sessionCookie), Dtos.MeResponse.class);
        assertEquals(HttpStatus.OK, me.getStatus());

        // Mutation without the header is rejected.
        HttpClientResponseException noHeader = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.POST("/api/channels", Map.of("name", "csrf-team"))
                                .cookie(sessionCookie).cookie(csrfCookie)));
        assertEquals(HttpStatus.FORBIDDEN, noHeader.getStatus());

        // Header + cookie double-submit passes.
        HttpResponse<Dtos.ChannelDto> created = client.toBlocking().exchange(
                HttpRequest.POST("/api/channels", Map.of("name", "csrf-team"))
                        .cookie(sessionCookie).cookie(csrfCookie)
                        .header("X-CSRF-Token", session.csrfToken()),
                Dtos.ChannelDto.class);
        assertEquals(HttpStatus.CREATED, created.getStatus());
        assertNotNull(created.body());
    }

    @Test
    void loginEndpointIsRateLimitedPerIp() {
        HttpStatus last = null;
        for (int i = 0; i < 11; i++) {
            try {
                client.toBlocking().exchange(HttpRequest.GET("/api/auth/login")
                        .header("X-Forwarded-For", "203.0.113.77"));
                last = HttpStatus.OK;
            } catch (HttpClientResponseException e) {
                last = e.getStatus();
            }
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, last);
    }

    @Test
    void disabledUserIsRejected() {
        TestData.Fixture fx = testData.user("filter-disabled@test.local", false);
        // Token works while active…
        client.toBlocking().exchange(HttpRequest.GET("/api/auth/me")
                .header("Authorization", "Bearer " + fx.bearer()));
        // …and stops working the moment the user is disabled.
        users.updateStatus(fx.user().id(), com.quickpaste.model.User.STATUS_DISABLED);
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.GET("/api/auth/me")
                        .header("Authorization", "Bearer " + fx.bearer())));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }
}
