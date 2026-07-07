package com.quickpaste.security;

import com.quickpaste.config.QuickPasteConfig;
import com.quickpaste.error.ApiException;
import com.quickpaste.model.ApiToken;
import com.quickpaste.model.User;
import com.quickpaste.repository.ApiTokenRepository;
import com.quickpaste.repository.UserRepository;
import com.quickpaste.util.CryptoUtil;
import com.quickpaste.util.Hashing;
import com.quickpaste.util.Ids;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Per-user API tokens (SPEC §9.3): 256-bit CSPRNG secret shown once as
 * {@code <prefix>.<secret>}; only SHA-256(secret) is stored.
 */
@Singleton
public class TokenService {

    private final QuickPasteConfig config;
    private final ApiTokenRepository tokens;
    private final UserRepository users;

    public TokenService(QuickPasteConfig config, ApiTokenRepository tokens, UserRepository users) {
        this.config = config;
        this.tokens = tokens;
        this.users = users;
    }

    public record MintedToken(ApiToken entity, String plainToken) {
    }

    public MintedToken mint(User user, String name, String scope) {
        if (name == null || name.isBlank() || name.length() > 64) {
            throw ApiException.validation("Token name must be 1-64 characters.");
        }
        String effectiveScope = scope == null || scope.isBlank() ? ApiToken.SCOPE_WRITE : scope;
        if (!ApiToken.SCOPE_READ.equals(effectiveScope) && !ApiToken.SCOPE_WRITE.equals(effectiveScope)) {
            throw ApiException.validation("Token scope must be 'read' or 'write'.");
        }
        String prefix = "qp_" + HexFormat.of().formatHex(CryptoUtil.randomBytes(4));
        String secret = CryptoUtil.randomUrlToken(32);
        Instant now = Instant.now();
        Instant expiresAt = config.getTokenTtlDays() > 0
                ? now.plus(Duration.ofDays(config.getTokenTtlDays()))
                : null;
        ApiToken token = new ApiToken(
                Ids.newUlid(), user.id(), name.trim(), Hashing.sha256Hex(secret), prefix,
                effectiveScope, now, null, expiresAt, null);
        tokens.save(token);
        return new MintedToken(token, prefix + "." + secret);
    }

    /** Resolves an {@code Authorization: Bearer} value; empty on any mismatch. */
    public Optional<QpPrincipal> resolve(String bearerValue) {
        int dot = bearerValue.indexOf('.');
        if (dot <= 0 || dot == bearerValue.length() - 1) {
            return Optional.empty();
        }
        String prefix = bearerValue.substring(0, dot);
        String secretHash = Hashing.sha256Hex(bearerValue.substring(dot + 1));
        Instant now = Instant.now();
        for (ApiToken token : tokens.findByPrefix(prefix)) {
            if (!CryptoUtil.constantTimeEquals(token.tokenHash(), secretHash)) {
                continue;
            }
            if (token.revokedAt() != null || (token.expiresAt() != null && token.expiresAt().isBefore(now))) {
                return Optional.empty();
            }
            tokens.touchLastUsed(token.id(), now);
            return users.findById(token.userId()).map(u -> QpPrincipal.token(u, token));
        }
        return Optional.empty();
    }

    public List<ApiToken> list(User user) {
        return tokens.findByUserIdOrderByCreatedAtDesc(user.id());
    }

    public void revoke(User user, String tokenId) {
        ApiToken token = tokens.findById(tokenId)
                .filter(t -> t.userId().equals(user.id()))
                .orElseThrow(ApiException::notFound);
        if (token.revokedAt() == null) {
            tokens.revoke(token.id(), Instant.now());
        }
    }
}
