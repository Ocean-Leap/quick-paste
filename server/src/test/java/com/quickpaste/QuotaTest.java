package com.quickpaste;

import com.quickpaste.model.dto.Dtos;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.multipart.MultipartBody;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** FR-43 quota + FR-20 simple-upload cap, with 1 MB limits for fast tests. */
@MicronautTest(transactional = false)
class QuotaTest extends DbTestBase {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    TestData testData;

    @Inject
    com.quickpaste.repository.StoredFileRepository files;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> props = super.getProperties();
        props.put("quickpaste.user-quota-mb", "1");
        props.put("quickpaste.simple-upload-mb", "1");
        return props;
    }

    private HttpClientResponseException uploadExpectingFailure(TestData.Fixture fx, byte[] bytes) {
        return assertThrows(HttpClientResponseException.class, () -> uploadRaw(fx, bytes));
    }

    private Dtos.FileUploadedDto uploadRaw(TestData.Fixture fx, byte[] bytes) {
        MultipartBody body = MultipartBody.builder()
                .addPart("file", "chunk.bin", MediaType.APPLICATION_OCTET_STREAM_TYPE, bytes)
                .build();
        return client.toBlocking().retrieve(
                HttpRequest.POST("/api/channels/" + fx.privateChannelId() + "/files", body)
                        .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
                        .header("Authorization", "Bearer " + fx.bearer()),
                Dtos.FileUploadedDto.class);
    }

    @Test
    void quotaIsEnforcedBeforeStoring() {
        TestData.Fixture fx = testData.user("quota-user@test.local", false);
        uploadRaw(fx, new byte[700 * 1024]); // 700 KB of the 1 MB quota
        HttpClientResponseException e = uploadExpectingFailure(fx, new byte[500 * 1024]);
        assertEquals(HttpStatus.CONFLICT, e.getStatus()); // quota_exceeded (SPEC §8.3)
    }

    @Test
    void filesAboveSimpleLimitAreRejected() {
        TestData.Fixture fx = testData.user("quota-simple@test.local", false);
        HttpClientResponseException e = uploadExpectingFailure(fx, new byte[1024 * 1024 + 1024]);
        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, e.getStatus());
    }

    @Test
    void parallelUploadsCannotRaceThePerUserQuota() throws Exception {
        TestData.Fixture fx = testData.user("quota-race@test.local", false);
        int threads = 5;
        byte[] payload = new byte[300 * 1024]; // 3 fit in the 1 MB quota, 4 don't
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(threads);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.atomic.AtomicInteger successes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger quotaRejections = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    uploadRaw(fx, payload);
                    successes.incrementAndGet();
                } catch (HttpClientResponseException e) {
                    if (e.getStatus() == HttpStatus.CONFLICT) {
                        quotaRejections.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // leaves counters unsatisfied; asserts below fail loudly
                } finally {
                    done.countDown();
                }
            }).start();
        }
        assertEquals(true, done.await(60, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(3, successes.get(), "quota check-then-commit must be serialized per user");
        assertEquals(2, quotaRejections.get());
        assertEquals(true, files.totalSizeByUser(fx.user().id()) <= 1024 * 1024L,
                "stored bytes must never exceed QP_USER_QUOTA_MB");
    }
}
