package com.quickpaste.util;

import com.quickpaste.error.ApiException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Outbound URL safety for server-side fetches (sink base URLs, etc.). Rejects
 * private/loopback/link-local targets so user-controlled URLs cannot SSRF the
 * origin or exfiltrate bearer tokens to internal hosts.
 */
public final class HostSafety {

    private HostSafety() {
    }

    /**
     * Validates a Steward sink base URL: https only, host present, and no
     * resolved address in private/loopback/link-local/multicast ranges.
     * Unresolvable hosts are allowed (delivery will fail later); literal
     * private IPs and localhost are always rejected.
     */
    public static String requirePublicHttpsBase(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > 512) {
            throw ApiException.validation("baseUrl is required (≤ 512 characters).");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation("baseUrl must be a valid https URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme)) {
            throw ApiException.validation("baseUrl must use https.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw ApiException.validation("baseUrl must include a host.");
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".local")) {
            throw ApiException.validation("baseUrl must not target localhost or .local hosts.");
        }
        if (isBlockedLiteralHost(lower)) {
            throw ApiException.validation("baseUrl must not target a private or loopback address.");
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (isBlockedAddress(addr)) {
                    throw ApiException.validation("baseUrl must not target a private or loopback address.");
                }
            }
        } catch (UnknownHostException e) {
            // Allow create; delivery will surface a transport error if DNS never resolves.
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    static boolean isBlockedLiteralHost(String host) {
        if (host.equals("::1") || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")) {
            return true;
        }
        String[] octets = host.split("\\.");
        if (octets.length != 4) {
            return false;
        }
        int[] v = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                v[i] = Integer.parseInt(octets[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (v[i] < 0 || v[i] > 255) {
                return false;
            }
        }
        return v[0] == 10
                || (v[0] == 172 && v[1] >= 16 && v[1] <= 31)
                || (v[0] == 192 && v[1] == 168)
                || (v[0] == 169 && v[1] == 254)
                || v[0] == 127
                || (v[0] == 0); // 0.0.0.0
    }

    static boolean isBlockedAddress(InetAddress addr) {
        return addr.isAnyLocalAddress()
                || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress();
    }
}
