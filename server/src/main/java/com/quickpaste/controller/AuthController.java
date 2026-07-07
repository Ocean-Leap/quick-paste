package com.quickpaste.controller;

import com.quickpaste.config.QuickPasteConfig;
import com.quickpaste.error.ApiException;
import com.quickpaste.model.dto.Dtos;
import com.quickpaste.security.AuthService;
import com.quickpaste.security.OidcService;
import com.quickpaste.security.QpPrincipal;
import com.quickpaste.security.SessionService;
import com.quickpaste.service.ChannelService;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.annotation.RequestAttribute;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.http.cookie.SameSite;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/** Google OIDC login/callback, logout, and /me (SPEC §8.2, §9.2). */
@Controller("/api/auth")
@ExecuteOn(TaskExecutors.BLOCKING)
public class AuthController {

    private final QuickPasteConfig config;
    private final OidcService oidc;
    private final AuthService authService;
    private final SessionService sessionService;
    private final ChannelService channelService;

    public AuthController(QuickPasteConfig config, OidcService oidc, AuthService authService,
                          SessionService sessionService, ChannelService channelService) {
        this.config = config;
        this.oidc = oidc;
        this.authService = authService;
        this.sessionService = sessionService;
        this.channelService = channelService;
    }

    /** Which sign-in the PWA should render; unauthenticated by design. */
    @Get("/mode")
    public Dtos.AuthModeDto mode() {
        return new Dtos.AuthModeDto(config.getAuthMode());
    }

    @Get("/login")
    public HttpResponse<?> login() {
        if (config.isDevAuthBypass()) {
            // Dev bypass authenticates every request implicitly; nothing to do here.
            return redirect("/");
        }
        if (config.isLocalMode()) {
            throw com.quickpaste.error.ApiException.validation(
                    "This instance uses local password sign-in (QP_AUTH_MODE=local).");
        }
        OidcService.LoginRedirect lr = oidc.beginLogin();
        return HttpResponse.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, lr.url())
                .cookie(lr.stateCookie());
    }

    /** FR-1a: password sign-in for LAN deployments without a public FQDN. */
    @io.micronaut.http.annotation.Post("/login-local")
    public HttpResponse<Void> loginLocal(HttpRequest<?> request,
                                         @io.micronaut.http.annotation.Body
                                         com.quickpaste.model.dto.Requests.LocalLoginRequest body) {
        AuthService.LoginOutcome outcome = authService.localLogin(
                body.password(), request.getHeaders().get(HttpHeaders.USER_AGENT));
        Duration ttl = Duration.ofHours(config.getSessionTtlHours());
        boolean secure = secureFor(request);
        return HttpResponse.<Void>noContent()
                .cookie(sessionCookie(outcome.session().cookieValue(), ttl, secure))
                .cookie(csrfCookie(outcome.session().csrfToken(), ttl, secure));
    }

    @Get("/callback")
    public HttpResponse<?> callback(HttpRequest<?> request,
                                    @QueryValue Optional<String> code,
                                    @QueryValue Optional<String> state,
                                    @QueryValue Optional<String> error) {
        if (error.isPresent() || code.isEmpty() || state.isEmpty()) {
            return redirect("/?login_error=" + encode(error.orElse("missing_code")));
        }
        try {
            OidcService.GoogleIdentity identity = oidc.finishCallback(request, code.get(), state.get());
            AuthService.LoginOutcome outcome = authService.completeLogin(
                    identity, request.getHeaders().get(HttpHeaders.USER_AGENT));
            Duration ttl = Duration.ofHours(config.getSessionTtlHours());
            boolean secure = secureFor(request);
            return redirect("/")
                    .cookie(sessionCookie(outcome.session().cookieValue(), ttl, secure))
                    .cookie(csrfCookie(outcome.session().csrfToken(), ttl, secure))
                    .cookie(expiredCookie(OidcService.COOKIE_OIDC, true));
        } catch (ApiException e) {
            return redirect("/?login_error=" + encode(e.code()));
        }
    }

    @Post("/logout")
    public HttpResponse<?> logout(@RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal) {
        if (principal.sessionId() != null) {
            sessionService.revoke(principal.sessionId());
        }
        return HttpResponse.noContent()
                .cookie(expiredCookie(SessionService.COOKIE_SESSION, true))
                .cookie(expiredCookie(SessionService.COOKIE_CSRF, false));
    }

    @Get("/me")
    public Dtos.MeResponse me(@RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal) {
        return new Dtos.MeResponse(
                Dtos.UserDto.from(principal.user()),
                channelService.listForUser(principal.user()));
    }

    /**
     * A cookie set over HTTPS must carry Secure so it never leaks to the plaintext
     * port. We honour the request's real scheme (X-Forwarded-Proto from the proxy),
     * falling back to the configured public URL — so signing in over https://host:8443
     * yields a Secure cookie even when QP_PUBLIC_URL is left at its http default.
     */
    private boolean secureFor(HttpRequest<?> request) {
        String proto = request.getHeaders().get("X-Forwarded-Proto");
        if (proto != null && !proto.isBlank()) {
            return "https".equalsIgnoreCase(proto.split(",")[0].trim());
        }
        return request.isSecure() || config.secureCookies();
    }

    private Cookie sessionCookie(String value, Duration ttl, boolean secure) {
        return Cookie.of(SessionService.COOKIE_SESSION, value)
                .httpOnly(true).secure(secure).sameSite(SameSite.Lax)
                .path("/").maxAge(ttl);
    }

    private Cookie csrfCookie(String value, Duration ttl, boolean secure) {
        // Double-submit CSRF token (SPEC §9.6): readable by the SPA, echoed in X-CSRF-Token.
        return Cookie.of(SessionService.COOKIE_CSRF, value)
                .httpOnly(false).secure(secure).sameSite(SameSite.Lax)
                .path("/").maxAge(ttl);
    }

    private Cookie expiredCookie(String name, boolean httpOnly) {
        return Cookie.of(name, "")
                .httpOnly(httpOnly).secure(config.secureCookies()).sameSite(SameSite.Lax)
                .path("/").maxAge(0);
    }

    private static MutableHttpResponse<Object> redirect(String location) {
        return HttpResponse.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
