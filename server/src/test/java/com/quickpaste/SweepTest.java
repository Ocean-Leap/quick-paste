package com.quickpaste;

import com.quickpaste.model.Clip;
import com.quickpaste.model.StoredFile;
import com.quickpaste.repository.ClipRepository;
import com.quickpaste.repository.StoredFileRepository;
import com.quickpaste.service.SweepService;
import com.quickpaste.util.Hashing;
import com.quickpaste.util.Ids;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FR-35 / SPEC §12.6: expired rows and blobs are removed, abandoned uploads are GC'd. */
@MicronautTest(transactional = false)
class SweepTest extends DbTestBase {

    @Inject
    TestData testData;

    @Inject
    ClipRepository clips;

    @Inject
    StoredFileRepository files;

    @Inject
    SweepService sweepService;

    @Test
    void sweepRemovesExpiredAndAbandoned() throws Exception {
        TestData.Fixture fx = testData.user("sweep@test.local", false);
        Instant now = Instant.now();
        String cid = fx.privateChannelId();

        // Expired text clip.
        Clip expiredClip = new Clip(Ids.newUlid(), cid, fx.user().id(), "mac", Clip.TYPE_TEXT,
                "old", null, Hashing.sha256Hex("old"), false, null, null,
                now.minus(Duration.ofHours(30)), now.minus(Duration.ofHours(6)));
        clips.save(expiredClip);

        // Live clip that must survive.
        Clip liveClip = new Clip(Ids.newUlid(), cid, fx.user().id(), "mac", Clip.TYPE_TEXT,
                "fresh", null, Hashing.sha256Hex("fresh"), false, null, null,
                now, now.plus(Duration.ofHours(24)));
        clips.save(liveClip);

        // Expired file with an on-disk blob.
        Path dir = storageDir.resolve(cid);
        Files.createDirectories(dir);
        String expiredFileId = Ids.newUlid();
        Path expiredBlob = dir.resolve(expiredFileId);
        Files.write(expiredBlob, new byte[128]);
        files.save(new StoredFile(expiredFileId, cid, fx.user().id(), "old.bin",
                "application/octet-stream", 128, expiredBlob.toString(), Hashing.sha256Hex(new byte[128]),
                now.minus(Duration.ofHours(80)), now.minus(Duration.ofHours(8))));

        // Abandoned upload: unreferenced, past QP_UPLOAD_IDLE_GC_HOURS, not yet expired.
        String abandonedId = Ids.newUlid();
        Path abandonedBlob = dir.resolve(abandonedId);
        Files.write(abandonedBlob, new byte[64]);
        files.save(new StoredFile(abandonedId, cid, fx.user().id(), "abandoned.bin",
                "application/octet-stream", 64, abandonedBlob.toString(), Hashing.sha256Hex(new byte[64]),
                now.minus(Duration.ofHours(25)), now.plus(Duration.ofHours(47))));

        sweepService.sweep();

        assertTrue(clips.findById(expiredClip.id()).isEmpty(), "expired clip must be swept");
        assertTrue(clips.findById(liveClip.id()).isPresent(), "live clip must survive");
        assertTrue(files.findById(expiredFileId).isEmpty(), "expired file row must be swept");
        assertFalse(Files.exists(expiredBlob), "expired blob must be deleted");
        assertTrue(files.findById(abandonedId).isEmpty(), "abandoned upload row must be swept");
        assertFalse(Files.exists(abandonedBlob), "abandoned blob must be deleted");
    }
}
