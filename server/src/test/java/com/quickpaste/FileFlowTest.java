package com.quickpaste;

import com.quickpaste.model.dto.Dtos;
import com.quickpaste.util.Hashing;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.multipart.MultipartBody;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest(transactional = false)
class FileFlowTest extends DbTestBase {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    TestData testData;

    private Dtos.FileUploadedDto upload(TestData.Fixture fx, String name, byte[] bytes) {
        MultipartBody body = MultipartBody.builder()
                .addPart("file", name, MediaType.APPLICATION_OCTET_STREAM_TYPE, bytes)
                .build();
        return client.toBlocking().retrieve(
                HttpRequest.POST("/api/channels/" + fx.privateChannelId() + "/files", body)
                        .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
                        .header("Authorization", "Bearer " + fx.bearer()),
                Dtos.FileUploadedDto.class);
    }

    @Test
    void uploadMetaDownloadRoundtrip() {
        TestData.Fixture fx = testData.user("files-roundtrip@test.local", false);
        byte[] payload = "file payload — bytes éè".getBytes(StandardCharsets.UTF_8);
        Dtos.FileUploadedDto uploaded = upload(fx, "notes é.txt", payload);
        assertEquals(payload.length, uploaded.size());

        Dtos.FileMetaDto meta = client.toBlocking().retrieve(
                HttpRequest.GET("/api/channels/" + fx.privateChannelId() + "/files/"
                                + uploaded.fileId() + "/meta")
                        .header("Authorization", "Bearer " + fx.bearer()),
                Dtos.FileMetaDto.class);
        assertEquals(Hashing.sha256Hex(payload), meta.checksum());
        assertEquals("notes é.txt", meta.name());

        HttpResponse<byte[]> download = client.toBlocking().exchange(
                HttpRequest.GET("/api/channels/" + fx.privateChannelId() + "/files/" + uploaded.fileId())
                        .header("Authorization", "Bearer " + fx.bearer()),
                byte[].class);
        assertArrayEquals(payload, download.body());
        String disposition = download.getHeaders().get(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition != null && disposition.contains("filename*=UTF-8''notes%20%C3%A9.txt"),
                "unexpected disposition: " + disposition);
    }

    @Test
    void fileClipLifecycleRemovesUnreferencedBlob() {
        TestData.Fixture fx = testData.user("files-clip@test.local", false);
        byte[] payload = new byte[4096];
        Dtos.FileUploadedDto uploaded = upload(fx, "blob.bin", payload);
        Path blobPath = storageDir.resolve(fx.privateChannelId()).resolve(uploaded.fileId());
        assertTrue(Files.exists(blobPath));

        Dtos.ClipDto clip = client.toBlocking().retrieve(
                HttpRequest.POST("/api/channels/" + fx.privateChannelId() + "/clips",
                                Map.of("type", "file", "fileId", uploaded.fileId(), "origin", "mac"))
                        .header("Authorization", "Bearer " + fx.bearer()),
                Dtos.ClipDto.class);
        assertEquals("file", clip.type());
        assertEquals("blob.bin", clip.file().name());

        // Deleting the last referencing clip removes blob + row (FR-34).
        client.toBlocking().exchange(
                HttpRequest.DELETE("/api/channels/" + fx.privateChannelId() + "/clips/" + clip.id())
                        .header("Authorization", "Bearer " + fx.bearer()));
        assertFalse(Files.exists(blobPath));
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.GET("/api/channels/" + fx.privateChannelId() + "/files/"
                                        + uploaded.fileId() + "/meta")
                                .header("Authorization", "Bearer " + fx.bearer())));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    @Test
    void unknownFileIdIsRejectedOnClipCreate() {
        TestData.Fixture fx = testData.user("files-unknown@test.local", false);
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.POST("/api/channels/" + fx.privateChannelId() + "/clips",
                                        Map.of("type", "file", "fileId", "01JUNKJUNKJUNKJUNKJUNKJUNK",
                                                "origin", "mac"))
                                .header("Authorization", "Bearer " + fx.bearer())));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatus());
    }
}
