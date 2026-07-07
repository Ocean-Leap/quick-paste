package com.quickpaste;

import com.quickpaste.model.dto.Dtos;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-1a: QP_AUTH_MODE=local — password sign-in for LAN deployments without an FQDN. */
@MicronautTest(transactional = false)
class LocalAuthTest extends DbTestBase {

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> props = super.getProperties();
        props.put("quickpaste.auth-mode", "local");
        props.put("quickpaste.local-password", "correct-horse-battery");
        return props;
    }

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void modeIsDiscoverableWithoutAuth() {
        Dtos.AuthModeDto mode = client.toBlocking().retrieve(
                HttpRequest.GET("/api/auth/mode"), Dtos.AuthModeDto.class);
        assertEquals("local", mode.mode());
    }

    @Test
    void wrongPasswordIsRejected() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.POST("/api/auth/login-local", Map.of("password", "nope"))));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }

    @Test
    void correctPasswordCreatesOwnerSession() {
        HttpResponse<Void> login = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/login-local",
                        Map.of("password", "correct-horse-battery")));
        assertEquals(HttpStatus.NO_CONTENT, login.getStatus());
        String sessionCookie = login.getCookies().findCookie("qp_session")
                .map(Cookie::getValue).orElse(null);
        assertNotNull(sessionCookie, "login must set the session cookie");

        Dtos.MeResponse me = client.toBlocking().retrieve(
                HttpRequest.GET("/api/auth/me").cookie(Cookie.of("qp_session", sessionCookie)),
                Dtos.MeResponse.class);
        assertEquals("owner@local", me.user().email());
        assertTrue(me.user().isAdmin(), "the single local owner is admin");
        assertEquals(1, me.channels().size());
        assertEquals("private", me.channels().get(0).type());
    }

    @Test
    void httpsForwardedProtoYieldsSecureCookie() {
        HttpResponse<Void> login = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/login-local",
                                Map.of("password", "correct-horse-battery"))
                        .header("X-Forwarded-Proto", "https"));
        Cookie session = login.getCookies().findCookie("qp_session").orElseThrow();
        assertTrue(session.isSecure(),
                "a session created over https must carry the Secure attribute");
    }

    @Test
    void emptyPasswordIsRejected() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.POST("/api/auth/login-local", Map.of("password", ""))));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }

    @Test
    void googleLoginEndpointIsDisabledInLocalMode() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.GET("/api/auth/login")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatus());
    }
}
