package com.quickpaste.service;

import com.quickpaste.config.QuickPasteConfig;
import com.quickpaste.model.StoredFile;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Lazy image thumbnails (FR-23): generated on first request, cached under
 * QP_STORAGE_DIR/.thumbs/{channelId}/{fileId}.jpg, removed with their blob.
 * Non-image or unreadable content simply yields empty (→ 404).
 */
@Singleton
public class ThumbnailService {

    private static final Logger LOG = LoggerFactory.getLogger(ThumbnailService.class);

    private final QuickPasteConfig config;
    private final FileService fileService;

    public ThumbnailService(QuickPasteConfig config, FileService fileService) {
        this.config = config;
        this.fileService = fileService;
    }

    public Optional<Path> thumbnailFor(StoredFile file) {
        if (!config.isThumbnailsEnabled() || !file.mime().startsWith("image/")) {
            return Optional.empty();
        }
        Path thumb = fileService.thumbPath(file);
        if (Files.isReadable(thumb)) {
            return Optional.of(thumb);
        }
        return generate(file, thumb);
    }

    /**
     * Decompression-bomb guard: a tiny PNG can claim enormous dimensions and expand
     * to gigabytes on decode. Anything above this pixel count is refused (≈200 MB
     * of ARGB raster) — a safety ceiling, not a product tunable.
     */
    private static final long MAX_SOURCE_PIXELS = 50_000_000L;

    private synchronized Optional<Path> generate(StoredFile file, Path thumb) {
        if (Files.isReadable(thumb)) {
            return Optional.of(thumb); // another request generated it meanwhile
        }
        try {
            BufferedImage source = readBounded(Path.of(file.storagePath()));
            if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
                return Optional.empty(); // not a decodable image (or refused as oversized)
            }
            int maxPx = config.getThumbnailMaxPx();
            double scale = Math.min(1.0,
                    (double) maxPx / Math.max(source.getWidth(), source.getHeight()));
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

            // Flatten to RGB on white so PNG/GIF transparency survives JPEG encoding.
            BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, width, height);
                g.drawImage(source, 0, 0, width, height, null);
            } finally {
                g.dispose();
            }

            Files.createDirectories(thumb.getParent());
            Path tmp = thumb.resolveSibling(thumb.getFileName() + ".part");
            try (var os = Files.newOutputStream(tmp)) {
                if (!ImageIO.write(out, "jpg", os)) {
                    return Optional.empty();
                }
            }
            Files.move(tmp, thumb, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(thumb);
        } catch (IOException e) {
            LOG.warn("Thumbnail generation failed for file {}: {}", file.id(), e.getMessage());
            return Optional.empty();
        }
    }

    /** Reads image dimensions from the header before decoding; refuses oversized images. */
    private static BufferedImage readBounded(Path path) throws IOException {
        try (javax.imageio.stream.ImageInputStream input =
                     ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) {
                return null;
            }
            java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > MAX_SOURCE_PIXELS) {
                    LOG.warn("Refusing thumbnail for image with {} pixels", pixels);
                    return null;
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }
}
