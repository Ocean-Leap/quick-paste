package com.quickpaste.security;

import com.quickpaste.config.QuickPasteConfig;
import com.quickpaste.model.AuthSession;
import com.quickpaste.model.User;
import com.quickpaste.repository.AuthSessionRepository;
import com.quickpaste.repository.UserRepository;
import com.quickpaste.util.CryptoUtil;
import com.quickpaste.util.Ids;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Server-side sessions (SPEC §9.4). The cookie value is {@code <sessionId>.<HMAC(sessionId)>}
 * so a forged id fails before any DB lookup.
 */
@Singleton
public class SessionService {

    public static final String COOKIE_SESSION = "qp_session";
    public static final String COOKIE_CSRF = "qp_csrf";

    private static final Logger LOG = LoggerFactory.getLogger(SessionService.class);

    private final QuickPasteConfig config;
    private final AuthSessionRepository sessions;
    private final UserRepository users;
    private final String secret;

    public SessionService(QuickPasteConfig config, AuthSessionRepository sessions, UserRepository users) {
        this.config = config;
        this.sessions = sessions;
        this.users = users;
        if (config.getSessionSecret() == null || config.getSessionSecret().isBlank()) {
            LOG.warn("QP_SESSION_SECRET is not set; using a random per-boot secret. "
                    + "Sessions and sink-token encryption will not survive a restart.");
            this.secret = CryptoUtil.randomUrlToken(32);
        } else {
            this.secret = config.getSessionSecret();
        }
    }

    /**
     * Runtime secret used for session HMACs and sink-token AES key derivation.
     * Always non-blank (falls back to a per-boot random value when the env var is unset).
     */
    public String materialSecret() {
        return secret;
    }

    /** AES-256 key derived from {@link #materialSecret()} for encrypting channel sink tokens. */
    public byte[] aesKey() {
        return CryptoUtil.aesKeyFromSecret(secret);
    }

    public record NewSession(String cookieValue, String csrfToken, AuthSession session) {
    }

    public NewSession create(User user, String userAgent) {
        Instant now = Instant.now();
        AuthSession session = new AuthSession(
                Ids.newUlid(),
                user.id(),
                now,
                now.plus(Duration.ofHours(config.getSessionTtlHours())),
                userAgent == null ? "" : userAgent.substring(0, Math.min(255, userAgent.length())),
                null);
        sessions.save(session);
        return new NewSession(sign(session.id()), CryptoUtil.randomUrlToken(32), session);
    }

    /** Resolves a cookie value to an active principal, or empty on any mismatch. */
    public Optional<QpPrincipal> resolve(String cookieValue) {
        int dot = cookieValue.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        String id = cookieValue.substring(0, dot);
        if (!CryptoUtil.constantTimeEquals(sign(id), cookieValue)) {
            return Optional.empty();
        }
        return sessions.findById(id)
                .filter(s -> s.revokedAt() == null && s.expiresAt().isAfter(Instant.now()))
                .flatMap(s -> users.findById(s.userId()).map(u -> QpPrincipal.session(u, s.id())));
    }

    public void revoke(String sessionId) {
        sessions.revoke(sessionId, Instant.now());
    }

    public void revokeAllForUser(String userId) {
        sessions.revokeAllForUser(userId, Instant.now());
    }

    private String sign(String sessionId) {
        return sessionId + "." + CryptoUtil.hmacSha256B64Url(secret, sessionId);
    }
}
