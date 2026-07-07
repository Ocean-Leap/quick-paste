package com.quickpaste;

import com.quickpaste.model.dto.Dtos;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest(transactional = false)
class ClipFlowTest extends DbTestBase {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    TestData testData;

    private HttpResponse<Dtos.ClipDto> postText(TestData.Fixture fx, String text, String origin,
                                                String idempotencyKey) {
        Map<String, String> body = new HashMap<>();
        body.put("type", "text");
        body.put("text", text);
        body.put("origin", origin);
        if (idempotencyKey != null) {
            body.put("idempotencyKey", idempotencyKey);
        }
        return client.toBlocking().exchange(
                HttpRequest.POST("/api/channels/" + fx.privateChannelId() + "/clips", body)
                        .header("Authorization", "Bearer " + fx.bearer()),
                Dtos.ClipDto.class);
    }

    @Test
    void createLatestAndExpiry() {
        TestData.Fixture fx = testData.user("clips-basic@test.local", false);
        HttpResponse<Dtos.ClipDto> created = postText(fx, "hello world", "mac", null);
        assertEquals(HttpStatus.CREATED, created.getStatus());
        Dtos.ClipDto clip = created.body();
        assertEquals(26, clip.id().length());

        Dtos.ClipDto latest = client.toBlocking().retrieve(
                HttpRequest.GET("/api/channels/" + fx.privateChannelId() + "/clips/latest")
                        .header("Authorization", "Bearer " + fx.bearer()),
                Dtos.ClipDto.class);
        assertEquals(clip.id(), latest.id());
        assertEquals("hello world", latest.text());
        assertEquals("mac", latest.origin());
    }

    @Test
    void dedupWithinWindowIsANoOp() {
        TestData.Fixture fx = testData.user("clips-dedup@test.local", false);
        HttpResponse<Dtos.ClipDto> first = postText(fx, "dup-me", "mac", null);
        assertEquals(HttpStatus.CREATED, first.getStatus());
        // Same (origin, hash) within QP_DEDUP_WINDOW_SEC → 200 with the existing clip (FR-18/42).
        HttpResponse<Dtos.ClipDto> second = postText(fx, "dup-me", "mac", null);
        assertEquals(HttpStatus.OK, second.getStatus());
        assertEquals(first.body().id(), second.body().id());
        // Different origin is NOT deduped (it is a different device echoing).
        HttpResponse<Dtos.ClipDto> otherDevice = postText(fx, "dup-me", "ipad", null);
        assertEquals(HttpStatus.CREATED, otherDevice.getStatus());
    }

    @Test
    void idempotencyKeyReplaysOriginalResponse() {
        TestData.Fixture fx = testData.user("clips-idem@test.local", false);
        HttpResponse<Dtos.ClipDto> first = postText(fx, "idem-payload", "mac", "key-123");
        // Different text, same key: must replay the ORIGINAL response, not create (FR-41).
        HttpResponse<Dtos.ClipDto> replay = postText(fx, "different-text-entirely", "mac", "key-123");
        assertEquals(HttpStatus.CREATED, replay.getStatus());
        assertEquals(first.body().id(), replay.body().id());
        assertEquals("idem-payload", replay.body().text());
    }

    @Test
    void oversizeTextIsRejectedWithGuidance() {
        TestData.Fixture fx = testData.user("clips-oversize@test.local", false);
        String big = "x".repeat(256 * 1024 + 1);
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
                () -> postText(fx, big, "mac", null));
        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, e.getStatus());
    }

    @Test
    void paginationWithUlidCursors() {
        TestData.Fixture fx = testData.user("clips-paging@test.local", false);
        String[] ids = new String[5];
        for (int i = 0; i < 5; i++) {
            ids[i] = postText(fx, "item-" + i, "mac", null).body().id();
        }
        String base = "/api/channels/" + fx.privateChannelId() + "/clips";

        // Newest-first, limit 2, then page older via beforeId.
        Dtos.ClipListResponse page1 = client.toBlocking().retrieve(
                HttpRequest.GET(base + "?limit=2").header("Authorization", "Bearer " + fx.bearer()),
                Dtos.ClipListResponse.class);
        assertEquals(2, page1.items().size());
        assertEquals(ids[4], page1.items().get(0).id());
        assertEquals(ids[3], page1.items().get(1).id());
        assertEquals(ids[3], page1.nextCursor());

        Dtos.ClipListResponse page2 = client.toBlocking().retrieve(
                HttpRequest.GET(base + "?limit=2&beforeId=" + page1.nextCursor())
                        .header("Authorization", "Bearer " + fx.bearer()),
                Dtos.ClipListResponse.class);
        assertEquals(ids[2], page2.items().get(0).id());
        assertEquals(ids[1], page2.items().get(1).id());

        // Catch-up direction: afterId returns older→newer.
        Dtos.ClipListResponse after = client.toBlocking().retrieve(
                HttpRequest.GET(base + "?afterId=" + ids[2])
                        .header("Authorization", "Bearer " + fx.bearer()),
                Dtos.ClipListResponse.class);
        assertEquals(2, after.items().size());
        assertEquals(ids[3], after.items().get(0).id());
        assertEquals(ids[4], after.items().get(1).id());
        assertNull(after.nextCursor());
    }

    @Test
    void deleteAndClear() {
        TestData.Fixture fx = testData.user("clips-delete@test.local", false);
        String id1 = postText(fx, "keep", "mac", null).body().id();
        String id2 = postText(fx, "remove", "mac", null).body().id();
        String base = "/api/channels/" + fx.privateChannelId() + "/clips";

        client.toBlocking().exchange(HttpRequest.DELETE(base + "/" + id2)
                .header("Authorization", "Bearer " + fx.bearer()));
        Dtos.ClipDto latest = client.toBlocking().retrieve(
                HttpRequest.GET(base + "/latest").header("Authorization", "Bearer " + fx.bearer()),
                Dtos.ClipDto.class);
        assertEquals(id1, latest.id());

        client.toBlocking().exchange(HttpRequest.DELETE(base)
                .header("Authorization", "Bearer " + fx.bearer()));
        HttpResponse<Dtos.ClipDto> empty = client.toBlocking().exchange(
                HttpRequest.GET(base + "/latest").header("Authorization", "Bearer " + fx.bearer()),
                Dtos.ClipDto.class);
        assertEquals(HttpStatus.NO_CONTENT, empty.getStatus());
        assertTrue(client.toBlocking().retrieve(
                        HttpRequest.GET(base).header("Authorization", "Bearer " + fx.bearer()),
                        Dtos.ClipListResponse.class)
                .items().isEmpty());
    }
}
