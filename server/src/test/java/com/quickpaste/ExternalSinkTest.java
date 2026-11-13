package com.quickpaste;

import com.quickpaste.model.dto.Dtos;
import com.quickpaste.sink.StewardHttpIngestClient;
import com.quickpaste.sink.StewardIngestClient;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest(transactional = false)
class ExternalSinkTest extends DbTestBase {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    TestData testData;

    @Inject
    FakeStewardIngestClient fakeSteward;

    @BeforeEach
    void resetFake() {
        fakeSteward.reset();
    }

    @Test
    void createExternalChannelAndDeliverNote() throws Exception {
        TestData.Fixture fx = testData.user("sink-basic@test.local", false);
        Dtos.ChannelDto channel = createExternal(fx, "Mind", "https://steward.example",
                "stw_testtoken123456", List.of("qp"));
        assertEquals("external", channel.type());
        assertNotNull(channel.sink());
        assertEquals("steward", channel.sink().provider());
        assertEquals("https://steward.example", channel.sink().baseUrl());
        assertEquals("stw_testtoke", channel.sink().tokenPrefix());
        assertEquals(List.of("qp"), channel.sink().defaultTags());

        HttpResponse<Dtos.ClipDto> created = postText(fx, channel.id(), "Boiler warranty until 2027", "mac", null);
        assertEquals(HttpStatus.CREATED, created.getStatus());
        Dtos.ClipDto clip = created.body();
        assertNotNull(clip.delivery());
        assertEquals("pending", clip.delivery().status());

        Dtos.ClipDto delivered = awaitDelivery(fx, channel.id(), clip.id(), "sent");
        assertEquals(1, fakeSteward.calls.size());
        StewardIngestClient.IngestBody body = fakeSteward.calls.get(0);
        assertEquals("note", body.kind());
        assertEquals("Boiler warranty until 2027", body.text());
        assertEquals(clip.id(), body.idempotency_key());
        assertEquals(List.of("qp"), body.tags());
        assertEquals("https://steward.example", fakeSteward.lastBaseUrl.get());
        assertEquals("stw_testtoken123456", fakeSteward.lastToken.get());
        assertEquals("note", delivered.delivery().kind());
        assertEquals("42", delivered.delivery().externalItemId());
    }

    @Test
    void linkAndQuoteInference() throws Exception {
        TestData.Fixture fx = testData.user("sink-kinds@test.local", false);
        Dtos.ChannelDto channel = createExternal(fx, "Mind", "https://steward.example",
                "stw_kinds_token_xx", null);

        Dtos.ClipDto link = postText(fx, channel.id(), "https://example.com/a", "mac", null).body();
        awaitDelivery(fx, channel.id(), link.id(), "sent");
        assertEquals("link", fakeSteward.calls.get(0).kind());
        assertEquals("https://example.com/a", fakeSteward.calls.get(0).url());
        assertNull(fakeSteward.calls.get(0).text());

        fakeSteward.reset();
        Dtos.ClipDto quotedUrl = postText(fx, channel.id(), "\"https://example.com/b\"", "mac", null).body();
        awaitDelivery(fx, channel.id(), quotedUrl.id(), "sent");
        assertEquals("link", fakeSteward.calls.get(0).kind());

        fakeSteward.reset();
        Dtos.ClipDto quote = postText(fx, channel.id(), "\"Plant a tree.\"", "mac", "quote-key").body();
        awaitDelivery(fx, channel.id(), quote.id(), "sent");
        assertEquals("quote", fakeSteward.calls.get(0).kind());

        Map<String, String> body = new HashMap<>();
        body.put("type", "text");
        body.put("text", "https://example.com/forced-note");
        body.put("origin", "mac");
        body.put("mindKind", "note");
        fakeSteward.reset();
        Dtos.ClipDto forced = client.toBlocking().exchange(
                HttpRequest.POST("/api/channels/" + channel.id() + "/clips", body)
                        .header("Authorization", "Bearer " + fx.bearer()),
                Dtos.ClipDto.class).body();
        awaitDelivery(fx, channel.id(), forced.id(), "sent");
        assertEquals("note", fakeSteward.calls.get(0).kind());
    }

    @Test
    void filesRejectedOnExternal() {
        TestData.Fixture fx = testData.user("sink-files@test.local", false);
        Dtos.ChannelDto channel = createExternal(fx, "Mind", "https://steward.example",
                "stw_file_token_xxx", null);
        Map<String, String> body = new HashMap<>();
        body.put("type", "file");
        body.put("fileId", "01ARZ3NDEKTSV4RRFFQ69G5FAV");
        body.put("origin", "mac");
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.POST("/api/channels/" + channel.id() + "/clips", body)
                                .header("Authorization", "Bearer " + fx.bearer()),
                        Dtos.ClipDto.class));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatus());
        assertTrue(fakeSteward.calls.isEmpty());
    }

    @Test
    void fatalAuthSurfacesAsFailedDelivery() throws Exception {
        TestData.Fixture fx = testData.user("sink-fatal@test.local", false);
        fakeSteward.nextResult.set(new StewardIngestClient.Result.Fatal(401, "bad token"));
        Dtos.ChannelDto channel = createExternal(fx, "Mind", "https://steward.example",
                "stw_bad_token_xxxx", null);
        Dtos.ClipDto clip = postText(fx, channel.id(), "secret note", "mac", null).body();
        assertEquals("pending", clip.delivery().status());
        Dtos.ClipDto failed = awaitDelivery(fx, channel.id(), clip.id(), "failed");
        assertNotNull(failed.delivery().error());
        assertTrue(failed.delivery().error().contains("bad token")
                || failed.delivery().error().contains("401"));
        // Terminal — fake should not be called again on subsequent polls within a short window.
        int calls = fakeSteward.calls.size();
        Thread.sleep(50);
        assertEquals(calls, fakeSteward.calls.size());
    }

    @Test
    void privateBaseUrlRejected() {
        TestData.Fixture fx = testData.user("sink-ssrf@test.local", false);
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Evil");
        body.put("type", "external");
        body.put("provider", "steward");
        body.put("baseUrl", "https://127.0.0.1");
        body.put("token", "stw_ssrf_token_xxx");
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.POST("/api/channels", body)
                                .header("Authorization", "Bearer " + fx.bearer()),
                        Dtos.ChannelDto.class));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatus());
    }

    @Test
    void httpBaseUrlRejected() {
        TestData.Fixture fx = testData.user("sink-http@test.local", false);
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Http");
        body.put("type", "external");
        body.put("provider", "steward");
        body.put("baseUrl", "http://steward.example");
        body.put("token", "stw_http_token_xxx");
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.POST("/api/channels", body)
                                .header("Authorization", "Bearer " + fx.bearer()),
                        Dtos.ChannelDto.class));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatus());
    }

    @Test
    void rotateTokenAndDeleteExternal() {
        TestData.Fixture fx = testData.user("sink-rotate@test.local", false);
        Dtos.ChannelDto channel = createExternal(fx, "Mind", "https://steward.example",
                "stw_old_token_xxxxx", null);
        Map<String, Object> patch = new HashMap<>();
        patch.put("token", "stw_new_token_yyyyy");
        patch.put("baseUrl", "https://steward2.example");
        Dtos.ChannelDto updated = client.toBlocking().retrieve(
                HttpRequest.PATCH("/api/channels/" + channel.id() + "/sink", patch)
                        .header("Authorization", "Bearer " + fx.bearer()),
                Dtos.ChannelDto.class);
        assertEquals("stw_new_toke", updated.sink().tokenPrefix());
        assertEquals("https://steward2.example", updated.sink().baseUrl());

        HttpResponse<Void> deleted = client.toBlocking().exchange(
                HttpRequest.DELETE("/api/channels/" + channel.id())
                        .header("Authorization", "Bearer " + fx.bearer()),
                Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatus());
    }

    private Dtos.ClipDto awaitDelivery(TestData.Fixture fx, String channelId, String clipId, String status)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        Dtos.ClipDto latest = null;
        while (System.nanoTime() < deadline) {
            latest = client.toBlocking().retrieve(
                    HttpRequest.GET("/api/channels/" + channelId + "/clips/latest")
                            .header("Authorization", "Bearer " + fx.bearer()),
                    Dtos.ClipDto.class);
            if (latest.id().equals(clipId) && latest.delivery() != null
                    && status.equals(latest.delivery().status())) {
                return latest;
            }
            Thread.sleep(25);
        }
        assertNotNull(latest);
        assertNotNull(latest.delivery());
        assertEquals(status, latest.delivery().status(),
                "delivery status timed out; last=" + latest.delivery().status()
                        + " err=" + latest.delivery().error());
        return latest;
    }

    private Dtos.ChannelDto createExternal(TestData.Fixture fx, String name, String baseUrl,
                                           String token, List<String> tags) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("type", "external");
        body.put("provider", "steward");
        body.put("baseUrl", baseUrl);
        body.put("token", token);
        if (tags != null) {
            body.put("defaultTags", tags);
        }
        return client.toBlocking().retrieve(
                HttpRequest.POST("/api/channels", body)
                        .header("Authorization", "Bearer " + fx.bearer()),
                Dtos.ChannelDto.class);
    }

    private HttpResponse<Dtos.ClipDto> postText(TestData.Fixture fx, String channelId, String text,
                                                String origin, String idempotencyKey) {
        Map<String, String> body = new HashMap<>();
        body.put("type", "text");
        body.put("text", text);
        body.put("origin", origin);
        if (idempotencyKey != null) {
            body.put("idempotencyKey", idempotencyKey);
        }
        return client.toBlocking().exchange(
                HttpRequest.POST("/api/channels/" + channelId + "/clips", body)
                        .header("Authorization", "Bearer " + fx.bearer()),
                Dtos.ClipDto.class);
    }

    @Singleton
    @Replaces(StewardHttpIngestClient.class)
    static class FakeStewardIngestClient implements StewardIngestClient {
        final List<IngestBody> calls = new CopyOnWriteArrayList<>();
        final AtomicReference<String> lastBaseUrl = new AtomicReference<>();
        final AtomicReference<String> lastToken = new AtomicReference<>();
        final AtomicReference<Result> nextResult = new AtomicReference<>();

        void reset() {
            calls.clear();
            lastBaseUrl.set(null);
            lastToken.set(null);
            nextResult.set(null);
        }

        @Override
        public Result ingest(String baseUrl, String bearerToken, IngestBody body) {
            lastBaseUrl.set(baseUrl);
            lastToken.set(bearerToken);
            calls.add(body);
            Result forced = nextResult.getAndSet(null);
            if (forced != null) {
                return forced;
            }
            return new Result.Ok(new IngestResponse(42L, true, false, body.kind()));
        }
    }
}
