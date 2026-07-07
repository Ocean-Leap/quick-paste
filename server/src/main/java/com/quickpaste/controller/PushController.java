package com.quickpaste.controller;

import com.quickpaste.error.ApiException;
import com.quickpaste.model.PushSubscription;
import com.quickpaste.model.dto.Dtos;
import com.quickpaste.model.dto.Requests;
import com.quickpaste.repository.PushSubscriptionRepository;
import com.quickpaste.security.QpPrincipal;
import com.quickpaste.util.Ids;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.RequestAttribute;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.time.Instant;

/** Web-push subscription registration (FR-38, SPEC §8.2). */
@Controller("/api/push/subscriptions")
@ExecuteOn(TaskExecutors.BLOCKING)
public class PushController {

    private final PushSubscriptionRepository subscriptions;

    public PushController(PushSubscriptionRepository subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Post
    public HttpResponse<Dtos.PushSubscribedDto> subscribe(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal,
            @Body Requests.PushSubscribeRequest body) {
        String endpoint = body.endpoint() == null ? "" : body.endpoint().trim();
        if (endpoint.isEmpty() || endpoint.length() > 512) {
            throw ApiException.validation("endpoint is required (max 512 chars).");
        }
        requireSafeEndpoint(endpoint);
        if (body.keys() == null || isBlank(body.keys().p256dh()) || isBlank(body.keys().auth())) {
            throw ApiException.validation("keys.p256dh and keys.auth are required.");
        }
        String device = body.device() == null || body.device().isBlank() ? "web" : body.device().trim();
        if (device.length() > 64) {
            throw ApiException.validation("device label must be at most 64 characters.");
        }
        // The endpoint is browser-owned and globally unique: re-registration (or a
        // different user signing in on the same browser) replaces the old row.
        subscriptions.findByEndpoint(endpoint).ifPresent(s -> subscriptions.deleteById(s.id()));
        PushSubscription subscription = new PushSubscription(
                Ids.newUlid(), principal.user().id(), endpoint,
                body.keys().p256dh().trim(), body.keys().auth().trim(), device, Instant.now());
        subscriptions.save(subscription);
        return HttpResponse.created(new Dtos.PushSubscribedDto(subscription.id()));
    }

    @Delete("/{id}")
    public HttpResponse<Void> unsubscribe(
            @RequestAttribute(QpPrincipal.ATTR) QpPrincipal principal, String id) {
        PushSubscription subscription = subscriptions.findById(id)
                .filter(s -> s.userId().equals(principal.user().id()))
                .orElseThrow(ApiException::notFound);
        subscriptions.deleteById(subscription.id());
        return HttpResponse.noContent();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * The server POSTs to this URL on every clip — restrict it to HTTPS push services
     * (SSRF guard). Plain HTTP is allowed for loopback only (local dev/tests).
     */
    private static void requireSafeEndpoint(String endpoint) {
        java.net.URI uri;
        try {
            uri = new java.net.URI(endpoint);
        } catch (java.net.URISyntaxException e) {
            throw ApiException.validation("endpoint must be a valid URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost();
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")
                || host.equals("::1");
        if (!"https".equals(scheme) && !("http".equals(scheme) && loopback)) {
            throw ApiException.validation("endpoint must be an https:// push service URL.");
        }
    }
}
