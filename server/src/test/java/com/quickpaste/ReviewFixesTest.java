package com.quickpaste;

import com.quickpaste.model.Clip;
import com.quickpaste.model.dto.Dtos;
import com.quickpaste.repository.ClipRepository;
import com.quickpaste.util.Hashing;
import com.quickpaste.util.Ids;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.websocket.WebSocketClient;
import io.micronaut.websocket.annotation.ClientWebSocket;
import io.micronaut.websocket.annotation.OnMessage;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regressions for the code-review findings (upload lock, catch-up gaps, dedup/push scoping, SSRF). */
@MicronautTest(transactional = false)
class ReviewFixesTest extends DbTestBase {

    @ClientWebSocket
    abstract static class WsClient implements AutoCloseable {

        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @OnMessage
        void onMessage(String message) {
            messages.add(message);
        }

        abstract void send(String message);
    }

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    EmbeddedServer server;

    @Inject
    WebSocketClient wsClient;

    @Inject
    TestData testData;

    @Inject
    ClipRepository clips;

    @Inject
    ObjectMapper mapper;

    private MutableHttpRequest<?> auth(MutableHttpRequest<?> request, TestData.Fixture fx) {
        return request.header("Authorization", "Bearer " + fx.bearer());
    }

    @Test
    void identicalClipsFromDifferentUsersAreNotDeduped() {
        TestData.Fixture owner = testData.user("fix-dedup-a@test.local", false);
        TestData.Fixture member = testData.user("fix-dedup-b@test.local", false);
        Dtos.ChannelDto team = client.toBlocking().retrieve(
                auth(HttpRequest.POST("/api/channels", Map.of("name", "dedup-team")), owner),
                Dtos.ChannelDto.class);
        client.toBlocking().exchange(auth(HttpRequest.POST(
                "/api/channels/" + team.id() + "/members",
                Map.of("email", "fix-dedup-b@test.local")), owner));

        // Same text, same generic device label, within the dedup window.
        Map<String, String> body = Map.of("type", "text", "text", "https://example.com/shared-link",
                "origin", "android");
        HttpResponse<Dtos.ClipDto> first = client.toBlocking().exchange(
                auth(HttpRequest.POST("/api/channels/" + team.id() + "/clips", body), owner),
                Dtos.ClipDto.class);
        HttpResponse<Dtos.ClipDto> second = client.toBlocking().exchange(
                auth(HttpRequest.POST("/api/channels/" + team.id() + "/clips", body), member),
                Dtos.ClipDto.class);
        assertEquals(HttpStatus.CREATED, first.getStatus());
        assertEquals(HttpStatus.CREATED, second.getStatus(),
                "another user's identical clip must not suppress this user's create");
        assertNotEquals(first.body().id(), second.body().id());

        // Same user + same device within the window still dedups (FR-42).
        HttpResponse<Dtos.ClipDto> echo = client.toBlocking().exchange(
                auth(HttpRequest.POST("/api/channels/" + team.id() + "/clips", body), owner),
                Dtos.ClipDto.class);
        assertEquals(HttpStatus.OK, echo.getStatus());
        assertEquals(first.body().id(), echo.body().id());
    }

    @Test
    void catchupDrainsBeyondBatchLimitAndEndsWithDoneMarker() throws Exception {
        TestData.Fixture fx = testData.user("fix-catchup@test.local", false);
        Instant now = Instant.now();
        List<Clip> bulk = new ArrayList<>();
        for (int i = 0; i < 520; i++) { // above the 500-row batch size
            String text = "bulk-" + i;
            bulk.add(new Clip(Ids.newUlid(), fx.privateChannelId(), fx.user().id(), "bulk",
                    Clip.TYPE_TEXT, text, null, Hashing.sha256Hex(text), false, null, null,
                    now, now.plus(Duration.ofHours(24))));
        }
        clips.saveAll(bulk);
        String lastId = bulk.get(bulk.size() - 1).id();

        String url = "ws://localhost:" + server.getPort() + "/api/stream";
        try (WsClient ws = Flux.from(wsClient.connect(WsClient.class,
                        HttpRequest.GET(url).header("Authorization", "Bearer " + fx.bearer())))
                .blockFirst(Duration.ofSeconds(10))) {
            assertNotNull(ws);
            ws.send("{\"type\":\"hello\",\"device\":\"junit\",\"cursors\":{}}");
            int replayed = 0;
            String previousId = "";
            Map<String, Object> done = null;
            long deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline) {
                String raw = ws.messages.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                if (raw == null) {
                    break;
                }
                Map<String, Object> event = mapper.readValue(raw, Argument.mapOf(String.class, Object.class));
                if ("clip.created".equals(event.get("event"))) {
                    @SuppressWarnings("unchecked")
                    String id = (String) ((Map<String, Object>) event.get("clip")).get("id");
                    assertTrue(id.compareTo(previousId) > 0, "replay must be in ascending ULID order");
                    previousId = id;
                    replayed++;
                } else if ("catchup.done".equals(event.get("event"))) {
                    done = event;
                    break;
                }
            }
            assertEquals(520, replayed, "the 500-row batch limit must not truncate catch-up");
            assertNotNull(done, "replay must end with catchup.done");
            assertEquals(lastId, done.get("cursor"));
        }
    }

    @Test
    void concurrentPatchesAtSameOffsetHaveExactlyOneWinner() throws Exception {
        TestData.Fixture fx = testData.user("fix-tuslock@test.local", false);
        String base = "/api/channels/" + fx.privateChannelId() + "/files/uploads";
        HttpResponse<Map<String, Object>> created = client.toBlocking().exchange(
                auth(HttpRequest.POST(base, null).header("Upload-Length", String.valueOf(256 * 1024)), fx),
                Argument.mapOf(String.class, Object.class));
        String location = created.getHeaders().get("Location");

        int threads = 4;
        byte[] chunk = new byte[64 * 1024];
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    client.toBlocking().exchange(
                            auth(HttpRequest.PATCH(location, chunk)
                                    .contentType("application/offset+octet-stream")
                                    .header("Upload-Offset", "0"), fx));
                    successes.incrementAndGet();
                } catch (HttpClientResponseException e) {
                    if (e.getStatus() == HttpStatus.CONFLICT) {
                        conflicts.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // barrier/interrupt failures leave counters unsatisfied and fail the asserts
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS));
        assertEquals(1, successes.get(), "exactly one concurrent PATCH at offset 0 may win");
        assertEquals(threads - 1, conflicts.get(), "losers must get 409, not corrupt the part file");

        HttpResponse<?> head = client.toBlocking().exchange(
                auth(HttpRequest.HEAD(location), fx));
        assertEquals(String.valueOf(chunk.length), head.getHeaders().get("Upload-Offset"),
                "part file must contain exactly one chunk");
    }

    @Test
    void idempotentReplayAfterDeletionCreatesAFreshClip() {
        TestData.Fixture fx = testData.user("fix-idem@test.local", false);
        String base = "/api/channels/" + fx.privateChannelId() + "/clips";
        Map<String, String> body = Map.of("type", "text", "text", "idem-refresh",
                "origin", "mac", "idempotencyKey", "fix-key-1");
        Dtos.ClipDto first = client.toBlocking().retrieve(auth(HttpRequest.POST(base, body), fx),
                Dtos.ClipDto.class);
        // Replay while the clip exists → same id.
        Dtos.ClipDto replay = client.toBlocking().retrieve(auth(HttpRequest.POST(base, body), fx),
                Dtos.ClipDto.class);
        assertEquals(first.id(), replay.id());
        // After deletion the cached id no longer resolves → a fresh clip is created.
        client.toBlocking().exchange(auth(HttpRequest.DELETE(base + "/" + first.id()), fx));
        Dtos.ClipDto fresh = client.toBlocking().retrieve(auth(HttpRequest.POST(base, body), fx),
                Dtos.ClipDto.class);
        assertNotEquals(first.id(), fresh.id());
    }

    @Test
    void pushEndpointsMustBeHttpsExceptLoopback() {
        TestData.Fixture fx = testData.user("fix-ssrf@test.local", false);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, subscribeStatus(fx, "http://internal-service.example.com/hook"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, subscribeStatus(fx, "notaurl"));
        assertEquals(HttpStatus.CREATED, subscribeStatus(fx, "https://fcm.googleapis.com/fcm/send/abc"));
        assertEquals(HttpStatus.CREATED, subscribeStatus(fx, "http://127.0.0.1:9999/dev-push"));
    }

    private HttpStatus subscribeStatus(TestData.Fixture fx, String endpoint) {
        try {
            return client.toBlocking().exchange(auth(HttpRequest.POST("/api/push/subscriptions", Map.of(
                    "endpoint", endpoint,
                    "keys", Map.of("p256dh", "k", "auth", "a"),
                    "device", "junit")), fx)).getStatus();
        } catch (HttpClientResponseException e) {
            return e.getStatus();
        }
    }
}
