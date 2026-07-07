package com.quickpaste;

import com.quickpaste.model.dto.Dtos;
import com.quickpaste.repository.PushSubscriptionRepository;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest(transactional = false)
class PushApiTest extends DbTestBase {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    TestData testData;

    @Inject
    PushSubscriptionRepository subscriptions;

    private static Map<String, Object> subscription(String endpoint) {
        return Map.of(
                "endpoint", endpoint,
                "keys", Map.of("p256dh", "test-p256dh-key", "auth", "test-auth"),
                "device", "pixel");
    }

    @Test
    void configReportsPushDisabledWithoutVapidKeys() {
        TestData.Fixture fx = testData.user("push-config@test.local", false);
        Dtos.ClientConfigDto config = client.toBlocking().retrieve(
                HttpRequest.GET("/api/config").header("Authorization", "Bearer " + fx.bearer()),
                Dtos.ClientConfigDto.class);
        assertFalse(config.pushEnabled());
        assertNull(config.vapidPublicKey());
        assertEquals(256, config.maxTextKb());
        assertEquals(20, config.simpleUploadMb());
    }

    @Test
    void subscribeValidatesAndUpserts() {
        TestData.Fixture alice = testData.user("push-alice@test.local", false);
        TestData.Fixture bob = testData.user("push-bob@test.local", false);
        String endpoint = "https://push.example.com/sub/shared-browser";

        HttpClientResponseException invalid = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.POST("/api/push/subscriptions", Map.of("device", "x"))
                                .header("Authorization", "Bearer " + alice.bearer())));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, invalid.getStatus());

        HttpResponse<Dtos.PushSubscribedDto> first = client.toBlocking().exchange(
                HttpRequest.POST("/api/push/subscriptions", subscription(endpoint))
                        .header("Authorization", "Bearer " + alice.bearer()),
                Dtos.PushSubscribedDto.class);
        assertEquals(HttpStatus.CREATED, first.getStatus());

        // Same browser endpoint, different signed-in user → the row moves to Bob.
        client.toBlocking().exchange(
                HttpRequest.POST("/api/push/subscriptions", subscription(endpoint))
                        .header("Authorization", "Bearer " + bob.bearer()),
                Dtos.PushSubscribedDto.class);
        var row = subscriptions.findByEndpoint(endpoint).orElseThrow();
        assertEquals(bob.user().id(), row.userId());

        // Alice cannot revoke Bob's subscription.
        HttpClientResponseException foreign = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.DELETE("/api/push/subscriptions/" + row.id())
                                .header("Authorization", "Bearer " + alice.bearer())));
        assertEquals(HttpStatus.NOT_FOUND, foreign.getStatus());

        client.toBlocking().exchange(
                HttpRequest.DELETE("/api/push/subscriptions/" + row.id())
                        .header("Authorization", "Bearer " + bob.bearer()));
        assertTrue(subscriptions.findByEndpoint(endpoint).isEmpty());
    }
}
