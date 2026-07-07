package com.quickpaste.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class CryptoUtil {

    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoUtil() {
    }

    public static byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        RANDOM.nextBytes(b);
        return b;
    }

    public static String randomUrlToken(int numBytes) {
        return b64Url(randomBytes(numBytes));
    }

    public static String b64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] b64UrlDecode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    public static String hmacSha256B64Url(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return b64Url(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
