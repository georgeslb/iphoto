package org.iphoto.configuration;

import org.bytedeco.javacpp.indexer.DoubleIndexer;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;

import java.awt.image.BufferedImage;

import static org.bytedeco.opencv.global.opencv_core.CV_64F;
import static org.bytedeco.opencv.global.opencv_core.meanStdDev;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGRA2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.Laplacian;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;

// Variance of the Laplacian (Pech-Pacheco et al., "Diatom autofocusing in brightfield microscopy:
// a comparative study") - a cheap, no-ML blur-detection heuristic: grayscale the image, run a
// Laplacian edge filter over it, take the variance of the result. Sharp images carry lots of
// fine-edge high-frequency content -> high variance; blurry ones lack it -> low variance, below
// some threshold. Used by FaceDetectionServiceImpl to reject blurry face crops at detection time -
// see TODO.md's "detect blurry faces and reject them at detection time" entry for the full design
// decision and why.
public final class BlurDetector {

    private BlurDetector() {
    }

    public static double laplacianVariance(BufferedImage image) {
        try (Java2DFrameConverter java2DFrameConverter = new Java2DFrameConverter();
             OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat()) {
            Frame frame = java2DFrameConverter.convert(image);
            // `mat` aliases the converter's own internal, reused buffer (JavaCV frame converters
            // are stateful) - only the Mats allocated below are ours to release.
            Mat mat = matConverter.convert(frame);

            Mat gray = new Mat();
            Mat laplacian = new Mat();
            Mat mean = new Mat();
            Mat stddev = new Mat();
            try {
                switch (mat.channels()) {
                    case 4 -> cvtColor(mat, gray, COLOR_BGRA2GRAY);
                    case 3 -> cvtColor(mat, gray, COLOR_BGR2GRAY);
                    default -> mat.copyTo(gray);
                }

                Laplacian(gray, laplacian, CV_64F);
                meanStdDev(laplacian, mean, stddev);

                try (DoubleIndexer stddevIndexer = stddev.createIndexer()) {
                    double sigma = stddevIndexer.get(0);
                    return sigma * sigma; // variance = stddev^2
                }
            } finally {
                gray.release();
                laplacian.release();
                mean.release();
                stddev.release();
            }
        }
    }
}
