package com.quickpaste;

import com.quickpaste.model.dto.Dtos;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.websocket.WebSocketClient;
import io.micronaut.websocket.annotation.ClientWebSocket;
import io.micronaut.websocket.annotation.OnMessage;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SPEC §8.5: authenticated handshake, ULID-cursor catch-up, live fan-out, per-channel isolation. */
@MicronautTest(transactional = false)
class WsStreamTest extends DbTestBase {

    @ClientWebSocket
    abstract static class TestWsClient implements AutoCloseable {

        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @OnMessage
        void onMessage(String message) {
            messages.add(message);
        }

        abstract void send(String message);
    }

    @Inject
    EmbeddedServer server;

    @Inject
    WebSocketClient wsClient;

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    TestData testData;

    @Inject
    ObjectMapper mapper;

    private TestWsClient connect(String bearer) {
        String url = "ws://localhost:" + server.getPort() + "/api/stream";
        MutableHttpRequest<?> request = HttpRequest.GET(url)
                .header("Authorization", "Bearer " + bearer);
        TestWsClient ws = Flux.from(wsClient.connect(TestWsClient.class, request))
                .blockFirst(Duration.ofSeconds(10));
        assertNotNull(ws);
        return ws;
    }

    private Map<String, Object> nextEvent(TestWsClient ws, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String raw = ws.messages.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (raw == null) {
                return null;
            }
            Map<String, Object> event = parse(raw);
            if (!"ping".equals(event.get("event"))) {
                return event;
            }
        }
        return null;
    }

    private Map<String, Object> parse(String raw) throws IOException {
        return mapper.readValue(raw, Argument.mapOf(String.class, Object.class));
    }

    @SuppressWarnings("unchecked")
    private static String clipId(Map<String, Object> event) {
        return (String) ((Map<String, Object>) event.get("clip")).get("id");
    }

    private Dtos.ClipDto postText(TestData.Fixture fx, String text) {
        return client.toBlocking().retrieve(
                HttpRequest.POST("/api/channels/" + fx.privateChannelId() + "/clips",
                                Map.of("type", "text", "text", text, "origin", "ws-test"))
                        .header("Authorization", "Bearer " + fx.bearer()),
                Dtos.ClipDto.class);
    }

    @Test
    void catchupThenLiveThenCursorReconnect() throws Exception {
        TestData.Fixture fx = testData.user("ws-flow@test.local", false);
        Dtos.ClipDto c1 = postText(fx, "one");
        Dtos.ClipDto c2 = postText(fx, "two");

        try (TestWsClient ws = connect(fx.bearer())) {
            ws.send("{\"type\":\"hello\",\"device\":\"junit\",\"cursors\":{}}");
            Map<String, Object> e1 = nextEvent(ws, 5000);
            Map<String, Object> e2 = nextEvent(ws, 5000);
            assertEquals("clip.created", e1.get("event"));
            assertEquals(c1.id(), clipId(e1)); // ULID order: oldest first
            assertEquals(c2.id(), clipId(e2));

            // Replay ends with the catchup.done marker carrying the replay cursor (NFR-3).
            Map<String, Object> done = nextEvent(ws, 5000);
            assertEquals("catchup.done", done.get("event"));
            assertEquals(fx.privateChannelId(), done.get("channelId"));
            assertEquals(c2.id(), done.get("cursor"));

            Dtos.ClipDto c3 = postText(fx, "three");
            Map<String, Object> live = nextEvent(ws, 5000);
            assertEquals("clip.created", live.get("event"));
            assertEquals(c3.id(), clipId(live));

            // Reconnect with the cursor at c2: replay must contain c3 and nothing older.
            try (TestWsClient ws2 = connect(fx.bearer())) {
                ws2.send("{\"type\":\"hello\",\"device\":\"junit2\",\"cursors\":{\""
                        + fx.privateChannelId() + "\":\"" + c2.id() + "\"}}");
                List<String> replayed = new ArrayList<>();
                Map<String, Object> event;
                while ((event = nextEvent(ws2, 1500)) != null) {
                    if ("clip.created".equals(event.get("event"))) {
                        replayed.add(clipId(event));
                    }
                }
                assertEquals(List.of(c3.id()), replayed);
            }
        }
    }

    @Test
    void eventsNeverLeakAcrossChannels() throws Exception {
        TestData.Fixture alice = testData.user("ws-alice@test.local", false);
        TestData.Fixture bob = testData.user("ws-bob@test.local", false);
        try (TestWsClient bobWs = connect(bob.bearer())) {
            bobWs.send("{\"type\":\"hello\",\"device\":\"bob\",\"cursors\":{}}");
            postText(alice, "secret for alice only");
            // Bob receives only his own channel's catchup.done — never anything
            // referencing Alice's channel and never a clip.created.
            Map<String, Object> event;
            while ((event = nextEvent(bobWs, 1500)) != null) {
                assertTrue(!"clip.created".equals(event.get("event")),
                        "Bob must not receive clip events, got: " + event);
                assertTrue(!alice.privateChannelId().equals(event.get("channelId")),
                        "Bob must not see Alice's channelId, got: " + event);
            }
        }
    }

    @Test
    void handshakeRequiresAuthentication() {
        String url = "ws://localhost:" + server.getPort() + "/api/stream";
        assertThrows(Exception.class, () -> Flux
                .from(wsClient.connect(TestWsClient.class, HttpRequest.GET(url)))
                .blockFirst(Duration.ofSeconds(5)));
    }
}
