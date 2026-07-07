package com.quickpaste;

import com.sun.net.httpserver.HttpServer;
import com.quickpaste.repository.PushSubscriptionRepository;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-38 end-to-end delivery: real VAPID signing + RFC 8291 payload encryption against a
 * local mock push service, and pruning of dead (410) subscriptions.
 */
@MicronautTest(transactional = false)
class PushDeliveryTest extends DbTestBase {

    private static final KeyPair VAPID = generateP256();
    private static final KeyPair CLIENT = generateP256();
    private static final HttpServer MOCK;
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final CountDownLatch FIRST = new CountDownLatch(1);
    private static final CountDownLatch SECOND = new CountDownLatch(2);

    static {
        try {
            MOCK = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            MOCK.createContext("/push", exchange -> {
                int n = REQUESTS.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                // First delivery succeeds; later ones report the subscription gone.
                exchange.sendResponseHeaders(n == 1 ? 201 : 410, -1);
                exchange.close();
                FIRST.countDown();
                SECOND.countDown();
            });
            MOCK.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stopMock() {
        MOCK.stop(0);
    }

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> props = super.getProperties();
        props.put("quickpaste.vapid-public-key", encodePublic((ECPublicKey) VAPID.getPublic()));
        props.put("quickpaste.vapid-private-key", encodePrivate((ECPrivateKey) VAPID.getPrivate()));
        props.put("quickpaste.vapid-subject", "mailto:test@test.local");
        return props;
    }

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    TestData testData;

    @Inject
    PushSubscriptionRepository subscriptions;

    @Test
    void deliversEncryptedPushAndPrunesDeadSubscriptions() throws Exception {
        TestData.Fixture fx = testData.user("push-delivery@test.local", false);
        String endpoint = "http://127.0.0.1:" + MOCK.getAddress().getPort() + "/push/sub-1";

        client.toBlocking().exchange(HttpRequest.POST("/api/push/subscriptions", Map.of(
                        "endpoint", endpoint,
                        "keys", Map.of(
                                "p256dh", encodePublic((ECPublicKey) CLIENT.getPublic()),
                                "auth", Base64.getUrlEncoder().withoutPadding()
                                        .encodeToString(new byte[]{1, 2, 3, 4, 5, 6, 7, 8,
                                                9, 10, 11, 12, 13, 14, 15, 16})),
                        "device", "phone"))
                .header("Authorization", "Bearer " + fx.bearer()));

        // A clip from a DIFFERENT device label must reach the mock push service.
        postClip(fx, "push me");
        assertTrue(FIRST.await(15, TimeUnit.SECONDS), "expected a push delivery to the mock service");

        // Next delivery gets 410 → the subscription must be pruned.
        postClip(fx, "push me again");
        assertTrue(SECOND.await(15, TimeUnit.SECONDS), "expected a second delivery attempt");
        long deadline = System.currentTimeMillis() + 5000;
        while (subscriptions.findByEndpoint(endpoint).isPresent()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertTrue(subscriptions.findByEndpoint(endpoint).isEmpty(),
                "410 response must prune the subscription");
        assertEquals(2, REQUESTS.get());
    }

    private void postClip(TestData.Fixture fx, String text) {
        client.toBlocking().exchange(
                HttpRequest.POST("/api/channels/" + fx.privateChannelId() + "/clips",
                                Map.of("type", "text", "text", text, "origin", "mac"))
                        .header("Authorization", "Bearer " + fx.bearer()));
    }

    private static KeyPair generateP256() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Uncompressed P-256 point (0x04 || X || Y), base64url — the web-push wire format. */
    private static String encodePublic(ECPublicKey key) {
        byte[] x = to32Bytes(key.getW().getAffineX());
        byte[] y = to32Bytes(key.getW().getAffineY());
        byte[] point = new byte[65];
        point[0] = 0x04;
        System.arraycopy(x, 0, point, 1, 32);
        System.arraycopy(y, 0, point, 33, 32);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(point);
    }

    private static String encodePrivate(ECPrivateKey key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(to32Bytes(key.getS()));
    }

    private static byte[] to32Bytes(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        if (raw.length >= 32) {
            System.arraycopy(raw, raw.length - 32, out, 0, 32);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }
}
