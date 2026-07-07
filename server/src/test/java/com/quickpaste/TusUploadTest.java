package com.quickpaste;

import com.quickpaste.model.dto.Dtos;
import com.quickpaste.util.Hashing;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SPEC §8.6: tus-compatible create/HEAD/PATCH, offset conflicts, finalize → fileId. */
@MicronautTest(transactional = false)
class TusUploadTest extends DbTestBase {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    TestData testData;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> props = super.getProperties();
        props.put("quickpaste.max-file-mb", "1"); // makes the size-cap test cheap
        return props;
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private HttpResponse<byte[]> patch(TestData.Fixture fx, String uploadUrl, long offset, byte[] chunk) {
        return client.toBlocking().exchange(
                HttpRequest.PATCH(uploadUrl, chunk)
                        .contentType("application/offset+octet-stream")
                        .header("Upload-Offset", String.valueOf(offset))
                        .header("Authorization", "Bearer " + fx.bearer()),
                byte[].class);
    }

    @Test
    void chunkedUploadFinalizesIntoAFile() {
        TestData.Fixture fx = testData.user("tus-flow@test.local", false);
        byte[] data = new byte[300 * 1024];
        new Random(42).nextBytes(data);

        String base = "/api/channels/" + fx.privateChannelId() + "/files/uploads";
        HttpResponse<Dtos.UploadCreatedDto> created = client.toBlocking().exchange(
                HttpRequest.POST(base, null)
                        .header("Upload-Length", String.valueOf(data.length))
                        .header("Upload-Metadata",
                                "filename " + b64("big données.bin") + ",filetype " + b64("application/x-test"))
                        .header("Authorization", "Bearer " + fx.bearer()),
                Dtos.UploadCreatedDto.class);
        assertEquals(HttpStatus.CREATED, created.getStatus());
        assertEquals("1.0.0", created.getHeaders().get("Tus-Resumable"));
        String location = created.getHeaders().get("Location");
        assertNotNull(location);
        String uploadId = created.body().uploadId();
        assertEquals(base + "/" + uploadId, location);

        // HEAD: fresh upload at offset 0.
        HttpResponse<?> head = client.toBlocking().exchange(
                HttpRequest.HEAD(location).header("Authorization", "Bearer " + fx.bearer()));
        assertEquals("0", head.getHeaders().get("Upload-Offset"));
        assertEquals(String.valueOf(data.length), head.getHeaders().get("Upload-Length"));

        // Chunk 1.
        byte[] chunk1 = Arrays.copyOfRange(data, 0, 200 * 1024);
        HttpResponse<byte[]> p1 = patch(fx, location, 0, chunk1);
        assertEquals(HttpStatus.NO_CONTENT, p1.getStatus());
        assertEquals(String.valueOf(chunk1.length), p1.getHeaders().get("Upload-Offset"));

        // Wrong offset → 409 and the offset is unchanged (resume works).
        HttpClientResponseException conflict = assertThrows(HttpClientResponseException.class,
                () -> patch(fx, location, 12345, new byte[10]));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatus());
        HttpResponse<?> headAfterConflict = client.toBlocking().exchange(
                HttpRequest.HEAD(location).header("Authorization", "Bearer " + fx.bearer()));
        assertEquals(String.valueOf(chunk1.length), headAfterConflict.getHeaders().get("Upload-Offset"));

        // Final chunk finalizes: the uploadId IS the fileId (SPEC §8.6).
        byte[] chunk2 = Arrays.copyOfRange(data, chunk1.length, data.length);
        HttpResponse<byte[]> p2 = patch(fx, location, chunk1.length, chunk2);
        assertEquals(String.valueOf(data.length), p2.getHeaders().get("Upload-Offset"));

        Dtos.FileMetaDto meta = client.toBlocking().retrieve(
                HttpRequest.GET("/api/channels/" + fx.privateChannelId() + "/files/" + uploadId + "/meta")
                        .header("Authorization", "Bearer " + fx.bearer()),
                Dtos.FileMetaDto.class);
        assertEquals("big données.bin", meta.name());
        assertEquals(data.length, meta.size());
        assertEquals("application/x-test", meta.mime());
        assertEquals(Hashing.sha256Hex(data), meta.checksum());

        // The finalized file works end to end: clip + download.
        Dtos.ClipDto clip = client.toBlocking().retrieve(
                HttpRequest.POST("/api/channels/" + fx.privateChannelId() + "/clips",
                                Map.of("type", "file", "fileId", uploadId, "origin", "mac"))
                        .header("Authorization", "Bearer " + fx.bearer()),
                Dtos.ClipDto.class);
        assertEquals(uploadId, clip.file().id());
        byte[] downloaded = client.toBlocking().retrieve(
                HttpRequest.GET("/api/channels/" + fx.privateChannelId() + "/files/" + uploadId)
                        .header("Authorization", "Bearer " + fx.bearer()),
                byte[].class);
        assertArrayEquals(data, downloaded);

        // The finished upload session is gone.
        HttpClientResponseException goneSession = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.HEAD(location).header("Authorization", "Bearer " + fx.bearer())));
        assertEquals(HttpStatus.NOT_FOUND, goneSession.getStatus());
    }

    @Test
    void oversizeAndForeignUploadsAreRejected() {
        TestData.Fixture fx = testData.user("tus-limits@test.local", false);
        TestData.Fixture other = testData.user("tus-other@test.local", false);
        String base = "/api/channels/" + fx.privateChannelId() + "/files/uploads";

        // Above QP_MAX_FILE_MB (1 MB in this class).
        HttpClientResponseException tooBig = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.POST(base, null)
                        .header("Upload-Length", String.valueOf(2 * 1024 * 1024))
                        .header("Authorization", "Bearer " + fx.bearer())));
        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, tooBig.getStatus());

        // Create a session, then a non-member must get 404 on it.
        HttpResponse<Dtos.UploadCreatedDto> created = client.toBlocking().exchange(
                HttpRequest.POST(base, null)
                        .header("Upload-Length", "1024")
                        .header("Authorization", "Bearer " + fx.bearer()),
                Dtos.UploadCreatedDto.class);
        String location = created.getHeaders().get("Location");
        HttpClientResponseException foreign = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.HEAD(location).header("Authorization", "Bearer " + other.bearer())));
        assertEquals(HttpStatus.NOT_FOUND, foreign.getStatus());
    }
}
