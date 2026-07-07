package com.quickpaste.security;

import com.quickpaste.model.ApiToken;
import com.quickpaste.model.User;
import io.micronaut.core.annotation.Nullable;

/** The authenticated principal, resolved by {@link AuthFilter} and stored as a request attribute. */
public record QpPrincipal(User user, String via, @Nullable String sessionId, @Nullable String tokenScope) {

    public static final String ATTR = "qp.principal";
    public static final String VIA_SESSION = "session";
    public static final String VIA_TOKEN = "token";
    public static final String VIA_DEV = "dev";

    public static QpPrincipal session(User user, String sessionId) {
        return new QpPrincipal(user, VIA_SESSION, sessionId, null);
    }

    public static QpPrincipal token(User user, ApiToken token) {
        return new QpPrincipal(user, VIA_TOKEN, null, token.scope());
    }

    public static QpPrincipal dev(User user) {
        return new QpPrincipal(user, VIA_DEV, null, null);
    }

    /** Read-scoped tokens may not mutate (SPEC §9.3). */
    public boolean canWrite() {
        return tokenScope == null || ApiToken.SCOPE_WRITE.equals(tokenScope);
    }

    public String rateKey() {
        if (sessionId != null) {
            return "s:" + sessionId;
        }
        return "u:" + user.id() + ":" + via;
    }
}
