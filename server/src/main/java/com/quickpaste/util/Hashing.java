package com.quickpaste.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers — content hashes and file checksums are SHA-256 hex (SPEC §8.1). */
public final class Hashing {

    private Hashing() {
    }

    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(newDigest().digest(bytes));
    }

    /** Copies {@code in} to {@code out}, returning the SHA-256 hex of the copied bytes. */
    public static String copyAndHash(InputStream in, OutputStream out) throws IOException {
        MessageDigest digest = newDigest();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            digest.update(buf, 0, n);
            out.write(buf, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
