package com.quickpaste.util;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class CryptoUtil {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;

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

    /** AES-256 key derived from an arbitrary secret (e.g. {@code QP_SESSION_SECRET}). */
    public static byte[] aesKeyFromSecret(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Encrypts UTF-8 plaintext; returns base64url(iv ‖ ciphertext‖tag). */
    public static String encryptAesGcm(byte[] key, String plaintext) {
        try {
            byte[] iv = randomBytes(GCM_IV_LEN);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv);
            buf.put(ct);
            return b64Url(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM encrypt failed", e);
        }
    }

    public static String decryptAesGcm(byte[] key, String blob) {
        try {
            byte[] raw = b64UrlDecode(blob);
            if (raw.length <= GCM_IV_LEN) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            byte[] iv = Arrays.copyOfRange(raw, 0, GCM_IV_LEN);
            byte[] ct = Arrays.copyOfRange(raw, GCM_IV_LEN, raw.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM decrypt failed", e);
        }
    }
}
