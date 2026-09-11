package org.iphoto.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

// Unit test for FaceDetectionServiceImpl.generateFaceThumbnail in isolation (a synthetic
// BufferedImage), not the full ONNX detection/embedding pipeline - that's stubbed out entirely
// under the "test" profile (see TestFaceDetectionService), so nothing else in the suite exercises
// this method. Added alongside the fix for the Persons page (GET /api/faces/persons) ballooning to
// tens of MB / several seconds to load (reported 2026-09-11): the face thumbnail used to be saved
// at its full original-photo crop resolution as a lossless PNG instead of a small, compressed
// JPEG.
class FaceDetectionServiceImplTest {

    private static BufferedImage solidColorImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(200, 120, 80));
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    // A flat solid color compresses to almost nothing under PNG too (lossless DEFLATE handles
    // uniform regions extremely well), which wouldn't reproduce the actual bug - real photo
    // texture/noise is what makes PNG so much larger than JPEG for a photographic face crop.
    // Per-pixel random noise, deterministic (fixed seed) for a stable test.
    private static BufferedImage noisyImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        return image;
    }

    @Test
    void downscalesACropLargerThanTheMaxSizeWhilePreservingAspectRatio() throws Exception {
        // A face crop from a high-resolution original could easily be this big - the whole point
        // of the bug this guards against.
        BufferedImage largeCrop = solidColorImage(1600, 800);

        byte[] thumbnailBytes = FaceDetectionServiceImpl.generateFaceThumbnail(largeCrop);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(thumbnailBytes));

        assertThat(decoded.getWidth()).isLessThanOrEqualTo(300);
        assertThat(decoded.getHeight()).isLessThanOrEqualTo(300);
        // 2:1 aspect ratio preserved (allowing for integer rounding).
        assertThat((double) decoded.getWidth() / decoded.getHeight()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void encodesAsAnActualJpegNotAPng() throws Exception {
        byte[] thumbnailBytes = FaceDetectionServiceImpl.generateFaceThumbnail(solidColorImage(200, 200));

        // JPEG's SOI marker (0xFFD8) - a PNG would start with its own distinct 0x89504E47 signature.
        assertThat(thumbnailBytes[0]).isEqualTo((byte) 0xFF);
        assertThat(thumbnailBytes[1]).isEqualTo((byte) 0xD8);
    }

    @Test
    void producesAMuchSmallerPayloadThanTheOldFullResolutionPngWouldHave() throws Exception {
        BufferedImage largeCrop = noisyImage(1600, 1600);

        byte[] thumbnailBytes = FaceDetectionServiceImpl.generateFaceThumbnail(largeCrop);

        java.io.ByteArrayOutputStream pngBaos = new java.io.ByteArrayOutputStream();
        ImageIO.write(largeCrop, "png", pngBaos);

        // Not a tight bound - just confirms the fix actually shrinks the payload by an order of
        // magnitude for a large crop, matching the reported multi-MB-per-face blowup.
        assertThat(thumbnailBytes.length).isLessThan(pngBaos.size() / 10);
    }
}
