package org.iphoto.configuration;

import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Preprocesses a 112x112 aligned face crop for ArcFace R50 ({@code w600k_r50.onnx}, InsightFace's
 * {@code buffalo_l} pack) and reads back the raw 512-dim embedding. Replaces DJL's built-in
 * {@code ImageFeatureExtractorFactory} (which was used for the previous embedding model): that
 * factory's `ToTensor`/`Normalize` pipeline does its work via NDArray arithmetic
 * (transpose/sub/div), which the ONNX Runtime engine's own NDArray implementation doesn't support
 * (confirmed live - see {@link ScrfdDetectionTranslator}'s processInput for the same issue and the
 * exact exception it threw) now that PyTorch is no longer on the classpath to silently absorb
 * those calls via its own NDArray. Built by hand instead, same plain-pixel-loop approach as
 * {@link ScrfdDetectionTranslator}: CHW, BGR channel order (swapRB, matching InsightFace's own
 * reference `arcface_onnx.py` preprocessing), `(pixel-127.5)/127.5`.
 */
public class ArcFaceEmbeddingTranslator implements Translator<Image, float[]> {

    private static final int INPUT_SIZE = 112;

    @Override
    public Batchifier getBatchifier() {
        return null; // single image per call, and the model's output carries no batch axis either
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        BufferedImage source = (BufferedImage) input.getWrappedImage();
        BufferedImage resized;
        if (source.getWidth() == INPUT_SIZE && source.getHeight() == INPUT_SIZE) {
            resized = source;
        } else {
            // Defensive - both real callers (FaceAligner.align, and FaceDetectionService's
            // `subImage.resize(112, 112, false)` fallback) already hand in a 112x112 crop, but a
            // translator shouldn't assume a caller invariant it doesn't itself enforce.
            resized = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(source, 0, 0, INPUT_SIZE, INPUT_SIZE, null);
            g.dispose();
        }

        int[] pixels = resized.getRGB(0, 0, INPUT_SIZE, INPUT_SIZE, null, 0, INPUT_SIZE);
        int planeSize = INPUT_SIZE * INPUT_SIZE;
        float[] data = new float[3 * planeSize];
        for (int i = 0; i < planeSize; i++) {
            int rgb = pixels[i];
            float r = (rgb >> 16) & 0xFF;
            float gc = (rgb >> 8) & 0xFF;
            float b = rgb & 0xFF;
            data[i] = (b - 127.5f) / 127.5f;               // channel 0 = B
            data[planeSize + i] = (gc - 127.5f) / 127.5f;  // channel 1 = G
            data[2 * planeSize + i] = (r - 127.5f) / 127.5f; // channel 2 = R
        }
        NDArray array = ctx.getNDManager().create(data, new Shape(1, 3, INPUT_SIZE, INPUT_SIZE));
        return new NDList(array);
    }

    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        return list.singletonOrThrow().toFloatArray();
    }
}
