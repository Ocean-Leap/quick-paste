package com.quickpaste.controller;

import com.quickpaste.config.QuickPasteConfig;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.util.Locale;
import java.util.Optional;

/**
 * Caddy on-demand TLS gate (`on_demand_tls { ask }`): Caddy calls this before
 * minting an internal-CA certificate for an SNI. Approving only private/loopback
 * hosts, *.local, and the configured public host bounds issuance so an attacker
 * can't force unbounded cert generation with random SNIs. Called only over the
 * internal compose network; unauthenticated by design (outside /api).
 */
@Controller("/internal/tls-authorize")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TlsAuthorizeController {

    private final QuickPasteConfig config;

    public TlsAuthorizeController(QuickPasteConfig config) {
        this.config = config;
    }

    @Get
    public HttpResponse<?> authorize(@QueryValue Optional<String> domain) {
        String host = domain.map(d -> d.trim().toLowerCase(Locale.ROOT)).orElse("");
        return allowed(host) ? HttpResponse.ok() : HttpResponse.status(HttpStatus.FORBIDDEN);
    }

    private boolean allowed(String host) {
        if (host.isEmpty() || host.length() > 253) {
            return false;
        }
        if (host.equals("localhost") || host.endsWith(".local") || host.equals(publicHost())) {
            return true;
        }
        return isPrivateOrLoopbackIp(host);
    }

    private String publicHost() {
        try {
            return Optional.ofNullable(java.net.URI.create(config.getPublicUrl()).getHost())
                    .map(h -> h.toLowerCase(Locale.ROOT)).orElse("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** RFC 1918 / loopback / link-local IPv4, plus loopback and unique-local IPv6. */
    private boolean isPrivateOrLoopbackIp(String host) {
        String[] octets = host.split("\\.");
        if (octets.length == 4) {
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
                    || v[0] == 127;
        }
        // IPv6 loopback / unique-local (fc00::/7) / link-local (fe80::/10)
        return host.equals("::1") || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80");
    }
}
