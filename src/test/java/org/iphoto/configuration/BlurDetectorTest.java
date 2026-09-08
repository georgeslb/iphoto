package org.iphoto.configuration;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

// Unit tests for the blur-scoring logic in isolation (synthetic images), not just the full
// face-detection integration path - per TODO.md's blurry-face-rejection entry, since the whole
// point of the Laplacian-variance metric is to discriminate sharp crops from blurry ones well
// before it's ever wired into real face detection.
class BlurDetectorTest {

    private static final int SIZE = 64;

    // Maximum high-frequency content: every pixel alternates between black and white, so a
    // Laplacian edge filter sees a "wall" of hard edges everywhere.
    private static BufferedImage checkerboard() {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                image.setRGB(x, y, (x + y) % 2 == 0 ? 0x000000 : 0xFFFFFF);
            }
        }
        return image;
    }

    // A flat, solid-color image - the degenerate "no edges anywhere" case a fully out-of-focus
    // crop would tend toward.
    private static BufferedImage solidColor() {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(128, 128, 128));
        g.fillRect(0, 0, SIZE, SIZE);
        g.dispose();
        return image;
    }

    // A genuine blur, not just a flat image: box-averages the checkerboard's own pixels with a
    // wide-enough window to wash out its hard edges into a smooth gradient, the same way real
    // defocus/motion blur would smear a photo's actual edges.
    private static BufferedImage boxBlur(BufferedImage source, int radius) {
        BufferedImage result = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                long sum = 0;
                int count = 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int sx = Math.min(SIZE - 1, Math.max(0, x + dx));
                        int sy = Math.min(SIZE - 1, Math.max(0, y + dy));
                        sum += source.getRGB(sx, sy) & 0xFF; // blue channel is enough - image is grayscale
                        count++;
                    }
                }
                int avg = (int) (sum / count);
                int rgb = (avg << 16) | (avg << 8) | avg;
                result.setRGB(x, y, rgb);
            }
        }
        return result;
    }

    @Test
    void sharpCheckerboardHasMuchHigherVarianceThanABlurredVersionOfItself() {
        BufferedImage sharp = checkerboard();
        BufferedImage blurred = boxBlur(sharp, 4);

        double sharpVariance = BlurDetector.laplacianVariance(sharp);
        double blurredVariance = BlurDetector.laplacianVariance(blurred);

        assertThat(sharpVariance).isGreaterThan(blurredVariance * 10);
    }

    @Test
    void solidColorImageHasEffectivelyZeroVariance() {
        double variance = BlurDetector.laplacianVariance(solidColor());

        assertThat(variance).isLessThan(1.0);
    }

    @Test
    void sharpCheckerboardVarianceIsFarAboveTheCommonlyCitedBlurThreshold() {
        // The ~100 cutoff FaceDetectionServiceImpl uses, kept in sync here only as a sanity check
        // that a genuinely sharp image clears it by a wide margin - not a claim that 100 itself is
        // empirically tuned against this app's real photos (see FaceDetectionServiceImpl's own
        // comment on that).
        double variance = BlurDetector.laplacianVariance(checkerboard());

        assertThat(variance).isGreaterThan(100.0);
    }
}
