package org.iphoto.configuration;

import java.awt.image.BufferedImage;

// Variance of the Laplacian (Pech-Pacheco et al., "Diatom autofocusing in brightfield microscopy:
// a comparative study") - a cheap, no-ML blur-detection heuristic: grayscale the image, run a
// Laplacian edge filter over it, take the variance of the result. Sharp images carry lots of
// fine-edge high-frequency content -> high variance; blurry ones lack it -> low variance, below
// some threshold. Used by FaceDetectionServiceImpl to reject blurry face crops at detection time -
// see TODO.md's "detect blurry faces and reject them at detection time" entry for the full design
// decision and why.
//
// Pure Java on purpose. This runs once per detected face inside FaceDetectionServiceImpl's
// per-face loop; the previous implementation went BufferedImage -> JavaCV Frame -> OpenCV Mat via
// two native-backed frame converters and allocated several off-heap Mats (gray, and a CV_64F
// laplacian 8x that size) per call - a per-face native-allocation hot path whose transient buffers
// were only reclaimed on GC, and whose converters leave a small aliased Mat header behind that
// close() never frees. A 3x3 kernel over a face-sized luma buffer costs nothing and touches zero
// native memory. Numbers track OpenCV's Laplacian(ksize=1) + meanStdDev closely but not
// bit-exactly (grayscale weights match COLOR_BGR2GRAY; border handling matches BORDER_REFLECT_101);
// BLUR_VARIANCE_THRESHOLD in FaceDetectionServiceImpl is a literature default, never empirically
// tuned against this app's photos, so a small scale shift here changes nothing that was solid -
// re-check that constant if blurry-face rejection starts passing too much or too little.
public final class BlurDetector {

    private BlurDetector() {
    }

    public static double laplacianVariance(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w == 0 || h == 0) {
            return 0.0;
        }

        // Grayscale with OpenCV COLOR_BGR2GRAY's weights (0.299 R + 0.587 G + 0.114 B).
        // getRGB always hands back packed INT_ARGB regardless of the source image type.
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        double[] gray = new double[w * h];
        for (int i = 0; i < gray.length; i++) {
            int p = argb[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            gray[i] = 0.299 * r + 0.587 * g + 0.114 * b;
        }

        // 4-neighbour Laplacian kernel [[0,1,0],[1,-4,1],[0,1,0]] over every pixel, mirroring
        // out-of-bounds neighbours the way OpenCV's default border (BORDER_REFLECT_101: the edge
        // sample itself is not repeated) does, so border rows/cols contribute the same as OpenCV's
        // meanStdDev over the full Laplacian.
        double sum = 0.0;
        double sumSq = 0.0;
        long n = (long) w * h;
        for (int y = 0; y < h; y++) {
            int upRow = reflect101(y - 1, h) * w;
            int downRow = reflect101(y + 1, h) * w;
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int left = reflect101(x - 1, w);
                int right = reflect101(x + 1, w);
                double lap = gray[upRow + x] + gray[downRow + x] + gray[row + left] + gray[row + right]
                        - 4.0 * gray[row + x];
                sum += lap;
                sumSq += lap * lap;
            }
        }

        double mean = sum / n;
        double variance = sumSq / n - mean * mean; // E[x^2] - E[x]^2, population variance like meanStdDev
        return variance > 0.0 ? variance : 0.0;    // guard tiny negative from floating-point rounding
    }

    // BORDER_REFLECT_101 / BORDER_DEFAULT index mirror: -1 -> 1, size -> size-2. The loop handles
    // indices reflected repeatedly back out of range, which only happens on degenerate 1-2px images.
    private static int reflect101(int i, int size) {
        if (size == 1) {
            return 0;
        }
        while (i < 0 || i >= size) {
            i = i < 0 ? -i : 2 * size - 2 - i;
        }
        return i;
    }
}
