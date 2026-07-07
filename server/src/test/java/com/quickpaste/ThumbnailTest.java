package com.quickpaste;

import com.quickpaste.model.dto.Dtos;
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

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-23: lazy JPEG thumbnails for images, 404 for non-images, no upscaling. */
@MicronautTest(transactional = false)
class ThumbnailTest extends DbTestBase {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    TestData testData;

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLUE);
        g.fillOval(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private Dtos.FileUploadedDto upload(TestData.Fixture fx, String name, MediaType type, byte[] bytes) {
        MultipartBody body = MultipartBody.builder().addPart("file", name, type, bytes).build();
        return client.toBlocking().retrieve(
                HttpRequest.POST("/api/channels/" + fx.privateChannelId() + "/files", body)
                        .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
                        .header("Authorization", "Bearer " + fx.bearer()),
                Dtos.FileUploadedDto.class);
    }

    @Test
    void generatesBoundedJpegThumbnail() throws IOException {
        TestData.Fixture fx = testData.user("thumb-image@test.local", false);
        Dtos.FileUploadedDto uploaded = upload(fx, "photo.png", MediaType.IMAGE_PNG_TYPE, png(600, 400));

        HttpResponse<byte[]> response = client.toBlocking().exchange(
                HttpRequest.GET("/api/channels/" + fx.privateChannelId() + "/files/"
                                + uploaded.fileId() + "/thumbnail")
                        .header("Authorization", "Bearer " + fx.bearer()),
                byte[].class);
        assertEquals(HttpStatus.OK, response.getStatus());
        assertEquals("image/jpeg", response.getHeaders().get("Content-Type"));

        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(response.body()));
        assertNotNull(thumb, "thumbnail must be a decodable JPEG");
        assertEquals(320, Math.max(thumb.getWidth(), thumb.getHeight()));
        assertEquals(320, thumb.getWidth());
        assertEquals(213, thumb.getHeight()); // 400 * (320/600), aspect preserved
    }

    @Test
    void smallImagesAreNotUpscaled() throws IOException {
        TestData.Fixture fx = testData.user("thumb-small@test.local", false);
        Dtos.FileUploadedDto uploaded = upload(fx, "tiny.png", MediaType.IMAGE_PNG_TYPE, png(100, 50));
        byte[] body = client.toBlocking().retrieve(
                HttpRequest.GET("/api/channels/" + fx.privateChannelId() + "/files/"
                                + uploaded.fileId() + "/thumbnail")
                        .header("Authorization", "Bearer " + fx.bearer()),
                byte[].class);
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(body));
        assertEquals(100, thumb.getWidth());
        assertEquals(50, thumb.getHeight());
    }

    @Test
    void decompressionBombIsRefused() {
        // A few hundred bytes on disk claiming 60000x60000 (3.6 gigapixels): the
        // dimension guard must refuse it before decode instead of OOMing.
        TestData.Fixture fx = testData.user("thumb-bomb@test.local", false);
        Dtos.FileUploadedDto uploaded = upload(fx, "bomb.png", MediaType.IMAGE_PNG_TYPE,
                pngHeaderClaiming(60000, 60000));
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.GET("/api/channels/" + fx.privateChannelId() + "/files/"
                                        + uploaded.fileId() + "/thumbnail")
                                .header("Authorization", "Bearer " + fx.bearer())));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    /** Minimal PNG whose IHDR claims the given dimensions but carries almost no data. */
    private static byte[] pngHeaderClaiming(int width, int height) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
            java.io.DataOutputStream data = new java.io.DataOutputStream(out);
            byte[] ihdr = java.nio.ByteBuffer.allocate(17)
                    .put("IHDR".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                    .putInt(width).putInt(height)
                    .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0)
                    .array();
            data.writeInt(13);
            data.write(ihdr);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(ihdr);
            data.writeInt((int) crc.getValue());
            byte[] idat = "IDAT".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            data.writeInt(0);
            data.write(idat);
            crc.reset();
            crc.update(idat);
            data.writeInt((int) crc.getValue());
            byte[] iend = "IEND".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            data.writeInt(0);
            data.write(iend);
            crc.reset();
            crc.update(iend);
            data.writeInt((int) crc.getValue());
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void nonImagesGet404() {
        TestData.Fixture fx = testData.user("thumb-text@test.local", false);
        Dtos.FileUploadedDto uploaded = upload(fx, "notes.txt", MediaType.TEXT_PLAIN_TYPE,
                "not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.GET("/api/channels/" + fx.privateChannelId() + "/files/"
                                        + uploaded.fileId() + "/thumbnail")
                                .header("Authorization", "Bearer " + fx.bearer())));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        assertTrue(e.getMessage() == null || !e.getMessage().contains("Exception"));
    }
}
