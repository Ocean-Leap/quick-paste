package com.quickpaste.security;

import com.quickpaste.config.QuickPasteConfig;
import com.quickpaste.error.ErrorResponse;
import com.quickpaste.model.User;
import com.quickpaste.util.CryptoUtil;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.cookie.Cookie;

import java.util.Map;

/**
 * The origin auth boundary (SPEC §9): every /api request and the WS handshake resolves a
 * principal (bearer token, session cookie, or dev bypass), then rate limits, token-scope,
 * and CSRF (double-submit, cookie-auth mutations only) are enforced.
 */
@ServerFilter("/api/**")
public class AuthFilter implements Ordered {

    private static final String HEADER_CSRF = "X-CSRF-Token";

    private final QuickPasteConfig config;
    private final SessionService sessionService;
    private final TokenService tokenService;
    private final AuthService authService;
    private final RateLimiter rateLimiter;

    public AuthFilter(QuickPasteConfig config, SessionService sessionService, TokenService tokenService,
                      AuthService authService, RateLimiter rateLimiter) {
        this.config = config;
        this.sessionService = sessionService;
        this.tokenService = tokenService;
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> filter(HttpRequest<?> request) {
        String path = request.getPath();
        if ("/api/auth/mode".equals(path)) {
            return null; // unauthenticated capability discovery for the sign-in screen
        }
        if ("/api/auth/login".equals(path) || "/api/auth/callback".equals(path)
                || "/api/auth/login-local".equals(path)) {
            if (!rateLimiter.tryAcquire("login:" + clientIp(request), config.getLoginRateLimitPerMin())) {
                return rateLimited();
            }
            return null;
        }

        QpPrincipal principal = resolve(request);
        if (principal == null) {
            return error(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required.");
        }
        if (!User.STATUS_ACTIVE.equals(principal.user().status())) {
            return error(HttpStatus.UNAUTHORIZED, "unauthenticated", "This account is disabled.");
        }
        if (!rateLimiter.tryAcquire("req:" + principal.rateKey(), config.getRateLimitPerMin())) {
            return rateLimited();
        }

        HttpMethod method = request.getMethod();
        boolean mutating = method != HttpMethod.GET && method != HttpMethod.HEAD && method != HttpMethod.OPTIONS;
        if (mutating && !principal.canWrite()) {
            return error(HttpStatus.FORBIDDEN, "not_authorized", "This API token is read-only.");
        }
        if (mutating && QpPrincipal.VIA_SESSION.equals(principal.via())) {
            String header = request.getHeaders().get(HEADER_CSRF);
            String cookie = request.getCookies().findCookie(SessionService.COOKIE_CSRF)
                    .map(Cookie::getValue).orElse(null);
            if (header == null || cookie == null || !CryptoUtil.constantTimeEquals(header, cookie)) {
                return error(HttpStatus.FORBIDDEN, "not_authorized", "CSRF token missing or invalid.");
            }
        }

        request.getAttributes().put(QpPrincipal.ATTR, principal);
        return null;
    }

    @Nullable
    private QpPrincipal resolve(HttpRequest<?> request) {
        String authorization = request.getHeaders().get("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return tokenService.resolve(authorization.substring("Bearer ".length()).trim()).orElse(null);
        }
        QpPrincipal fromCookie = request.getCookies().findCookie(SessionService.COOKIE_SESSION)
                .flatMap(c -> sessionService.resolve(c.getValue()))
                .orElse(null);
        if (fromCookie != null) {
            return fromCookie;
        }
        if (config.isDevAuthBypass()) {
            return QpPrincipal.dev(authService.devUser());
        }
        return null;
    }

    /**
     * Client IP for login rate limiting. Forwarded headers are attacker-controlled
     * unless a trusted proxy set them, so we only consult X-Forwarded-For when
     * QP_TRUSTED_PROXY_COUNT > 0, and then take the entry that many hops from the
     * RIGHT (the value the closest trusted proxy observed) — a left-prepended spoof
     * is ignored. With no trusted proxy we use the real socket peer.
     */
    private String clientIp(HttpRequest<?> request) {
        int trusted = config.getTrustedProxyCount();
        if (trusted > 0) {
            String xff = request.getHeaders().get("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] parts = xff.split(",");
                int idx = parts.length - trusted;
                if (idx >= 0 && idx < parts.length) {
                    String ip = parts[idx].trim();
                    if (!ip.isEmpty()) {
                        return ip;
                    }
                }
            }
        }
        return request.getRemoteAddress().getAddress().getHostAddress();
    }

    private HttpResponse<?> rateLimited() {
        int retryAfter = rateLimiter.retryAfterSeconds();
        return HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(retryAfter))
                .body(ErrorResponse.of("rate_limited", "Too many requests.",
                        Map.of("retryAfterSeconds", retryAfter)));
    }

    private HttpResponse<?> error(HttpStatus status, String code, String message) {
        return HttpResponse.status(status).body(ErrorResponse.of(code, message, Map.of()));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
