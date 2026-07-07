package com.quickpaste.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.quickpaste.config.QuickPasteConfig;
import com.quickpaste.error.ApiException;
import com.quickpaste.util.CryptoUtil;
import com.quickpaste.util.Hashing;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Google OIDC, Authorization Code + PKCE (SPEC §9.2). The transient login state
 * (state/nonce/verifier) travels in a short-lived HMAC-signed cookie.
 */
@Singleton
public class OidcService {

    public static final String COOKIE_OIDC = "qp_oidc";
    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Logger LOG = LoggerFactory.getLogger(OidcService.class);

    private final QuickPasteConfig config;
    private final HttpClient tokenClient;
    private final ObjectMapper mapper;
    private volatile ConfigurableJWTProcessor<SecurityContext> jwtProcessor;

    public OidcService(QuickPasteConfig config,
                       @Client("https://oauth2.googleapis.com") HttpClient tokenClient,
                       ObjectMapper mapper) {
        this.config = config;
        this.tokenClient = tokenClient;
        this.mapper = mapper;
    }

    public boolean enabled() {
        return config.getGoogleClientId() != null && !config.getGoogleClientId().isBlank();
    }

    public record LoginRedirect(String url, Cookie stateCookie) {
    }

    public record GoogleIdentity(String email, String sub, String displayName) {
    }

    public LoginRedirect beginLogin() {
        requireEnabled();
        String state = CryptoUtil.randomUrlToken(24);
        String nonce = CryptoUtil.randomUrlToken(24);
        String verifier = CryptoUtil.randomUrlToken(48);
        String challenge = CryptoUtil.b64Url(sha256(verifier));
        String url = AUTH_URL
                + "?client_id=" + enc(config.getGoogleClientId())
                + "&redirect_uri=" + enc(redirectUri())
                + "&response_type=code"
                + "&scope=" + enc("openid email profile")
                + "&state=" + enc(state)
                + "&nonce=" + enc(nonce)
                + "&code_challenge=" + enc(challenge)
                + "&code_challenge_method=S256"
                + "&prompt=select_account";
        String payload = CryptoUtil.b64Url(
                ("{\"s\":\"" + state + "\",\"n\":\"" + nonce + "\",\"v\":\"" + verifier + "\",\"exp\":"
                        + Instant.now().plus(Duration.ofMinutes(5)).getEpochSecond() + "}")
                        .getBytes(StandardCharsets.UTF_8));
        Cookie cookie = Cookie.of(COOKIE_OIDC, payload + "." + CryptoUtil.hmacSha256B64Url(stateSecret(), payload))
                .httpOnly(true)
                .secure(config.secureCookies())
                .path("/")
                .maxAge(Duration.ofMinutes(5));
        return new LoginRedirect(url, cookie);
    }

    @SuppressWarnings("unchecked")
    public GoogleIdentity finishCallback(HttpRequest<?> request, String code, String state) {
        requireEnabled();
        Cookie cookie = request.getCookies().findCookie(COOKIE_OIDC)
                .orElseThrow(() -> ApiException.notAuthorized("Login state missing or expired."));
        Map<String, Object> loginState = verifyStateCookie(cookie.getValue());
        if (!CryptoUtil.constantTimeEquals((String) loginState.get("s"), state)) {
            throw ApiException.notAuthorized("OAuth state mismatch.");
        }
        if (((Number) loginState.get("exp")).longValue() < Instant.now().getEpochSecond()) {
            throw ApiException.notAuthorized("Login state expired; try again.");
        }

        Map<String, Object> tokenResponse;
        try {
            String body = tokenClient.toBlocking().retrieve(
                    HttpRequest.POST("/token", Map.of(
                                    "code", code,
                                    "client_id", config.getGoogleClientId(),
                                    "client_secret", config.getGoogleClientSecret(),
                                    "redirect_uri", redirectUri(),
                                    "grant_type", "authorization_code",
                                    "code_verifier", (String) loginState.get("v")))
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE));
            tokenResponse = mapper.readValue(body, Argument.mapOf(String.class, Object.class));
        } catch (Exception e) {
            LOG.warn("OIDC code exchange failed: {}", e.getMessage());
            throw ApiException.notAuthorized("Google login failed; try again.");
        }
        String idToken = (String) tokenResponse.get("id_token");
        if (idToken == null) {
            throw ApiException.notAuthorized("Google login failed: no ID token.");
        }
        return verifyIdToken(idToken, (String) loginState.get("n"));
    }

    private GoogleIdentity verifyIdToken(String idToken, String expectedNonce) {
        JWTClaimsSet claims;
        try {
            claims = processor().process(idToken, null);
        } catch (Exception e) {
            LOG.warn("ID token verification failed: {}", e.getMessage());
            throw ApiException.notAuthorized("Invalid Google ID token.");
        }
        String issuer = claims.getIssuer();
        if (!"https://accounts.google.com".equals(issuer) && !"accounts.google.com".equals(issuer)) {
            throw ApiException.notAuthorized("Invalid ID token issuer.");
        }
        if (claims.getAudience() == null || !claims.getAudience().contains(config.getGoogleClientId())) {
            throw ApiException.notAuthorized("Invalid ID token audience.");
        }
        try {
            String nonce = claims.getStringClaim("nonce");
            if (!CryptoUtil.constantTimeEquals(nonce, expectedNonce)) {
                throw ApiException.notAuthorized("Invalid ID token nonce.");
            }
            Boolean emailVerified = claims.getBooleanClaim("email_verified");
            String email = claims.getStringClaim("email");
            String name = claims.getStringClaim("name");
            if (email == null || email.isBlank() || Boolean.FALSE.equals(emailVerified)) {
                throw ApiException.notAuthorized("Google account has no verified email.");
            }
            return new GoogleIdentity(email.toLowerCase(java.util.Locale.ROOT), claims.getSubject(),
                    name == null ? email : name);
        } catch (java.text.ParseException e) {
            throw ApiException.notAuthorized("Malformed ID token claims.");
        }
    }

    private ConfigurableJWTProcessor<SecurityContext> processor() throws Exception {
        ConfigurableJWTProcessor<SecurityContext> p = jwtProcessor;
        if (p == null) {
            synchronized (this) {
                if (jwtProcessor == null) {
                    DefaultJWTProcessor<SecurityContext> created = new DefaultJWTProcessor<>();
                    JWKSource<SecurityContext> keySource = JWKSourceBuilder
                            .create(new URL(JWKS_URL))
                            .build();
                    created.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource));
                    jwtProcessor = created;
                }
                p = jwtProcessor;
            }
        }
        return p;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> verifyStateCookie(String value) {
        int dot = value.lastIndexOf('.');
        if (dot <= 0) {
            throw ApiException.notAuthorized("Malformed login state.");
        }
        String payload = value.substring(0, dot);
        if (!CryptoUtil.constantTimeEquals(
                CryptoUtil.hmacSha256B64Url(stateSecret(), payload), value.substring(dot + 1))) {
            throw ApiException.notAuthorized("Login state signature mismatch.");
        }
        try {
            return mapper.readValue(new String(CryptoUtil.b64UrlDecode(payload), StandardCharsets.UTF_8),
                    Argument.mapOf(String.class, Object.class));
        } catch (Exception e) {
            throw ApiException.notAuthorized("Malformed login state.");
        }
    }

    private String stateSecret() {
        // Falls back to the client secret so login state stays verifiable even when
        // QP_SESSION_SECRET is unset (SessionService then uses a per-boot secret).
        String s = config.getSessionSecret();
        return s == null || s.isBlank() ? config.getGoogleClientSecret() : s;
    }

    private String redirectUri() {
        return config.getPublicUrl().replaceAll("/$", "") + "/api/auth/callback";
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw ApiException.validation("Google OIDC is not configured (QP_GOOGLE_CLIENT_ID).");
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
