package org.iphoto.configuration;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Landmark;
import ai.djl.modality.cv.output.Point;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decodes SCRFD's raw ONNX output (InsightFace's {@code buffalo_l} detector, {@code det_10g.onnx})
 * into the same {@link DetectedObjects} contract {@link FaceDetectionTranslator} (RetinaFace) used
 * to produce, so {@code FaceDetectionService}/{@code FaceAligner} need no changes: a {@link Rectangle}
 * normalized to [0,1] fractions of the original image's width/height, and a {@link Landmark}'s 5
 * keypoints (left eye, right eye, nose, left mouth corner, right mouth corner - same WIDER-FACE-style
 * ordering RetinaFace used) given in ABSOLUTE pixel coordinates - that split (normalized rectangle,
 * pixel-space landmark points) is the existing, easy-to-miss contract {@code FaceDetectionService}
 * (multiplies bounds by width/height) and {@code FaceAligner} (uses landmark points directly, no
 * further scaling) both already depend on.
 *
 * <p>This is a direct, deliberate port of InsightFace's own reference postprocessing
 * (insightface/model_zoo/scrfd.py) - the decode math is genuinely different from RetinaFace's
 * variance-scaled prior-box offsets (see FaceDetectionTranslator): SCRFD predicts, per anchor
 * point, a direct pixel DISTANCE to each of the 4 box edges (distance2bbox) and to each of the 5
 * landmark points (distance2kps), already scaled by that anchor's stride. det_10g.onnx (buffalo_l)
 * outputs 9 tensors - grouped as [scores x3 strides, bbox_dists x3 strides, kps_dists x3 strides]
 * for strides [8, 16, 32], 2 anchors per spatial position (both anchors share the same center,
 * they differ only in the scale the network was trained to detect at that position) - this exact
 * output ordering/anchor-count is a documented SCRFD/buffalo_l convention, not guessed, but per the
 * migration plan this is the single highest-risk piece of the swap: verify it empirically (log
 * detected boxes/landmarks against a handful of real photos) before trusting it in the full
 * pipeline.
 */
public class ScrfdDetectionTranslator implements Translator<Image, DetectedObjects> {

    private static final int[] STRIDES = {8, 16, 32};
    private static final int NUM_ANCHORS = 2; // fixed by det_10g.onnx's own export, see class javadoc
    private static final int NUM_LANDMARKS = 5;

    private final double confThresh;
    private final double nmsThresh;
    private final int inputSize; // model is square, e.g. 640 - both width and height

    public ScrfdDetectionTranslator(double confThresh, double nmsThresh, int inputSize) {
        this.confThresh = confThresh;
        this.nmsThresh = nmsThresh;
        this.inputSize = inputSize;
    }

    // det_10g.onnx's outputs have no leading batch axis (each is a flat (numAnchors, C) tensor,
    // not (1, numAnchors, C)) - DJL's default StackBatchifier assumes dim 0 IS the batch
    // dimension and tries to split it accordingly, which - confirmed via a real prediction against
    // an actual photo - throws ("split_size can only be 0 if dimension size is 0, but got
    // dimension size of 3200", 3200 being one stride's anchor count, not a batch size) rather than
    // silently misbehaving. This translator only ever handles one image per call anyway (same as
    // FaceDetectionTranslator), so disabling batching entirely is correct, not just a workaround.
    @Override
    public Batchifier getBatchifier() {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        int origWidth = input.getWidth();
        int origHeight = input.getHeight();

        // Letterbox: resize preserving aspect ratio so the longer side fits inputSize, placed at
        // the TOP-LEFT of a zero-padded square canvas (not centered) - this exact placement (and
        // det_scale below) must match InsightFace's own preprocessing exactly, since the decoded
        // box/landmark coordinates are only meaningful relative to it.
        double imRatio = (double) origHeight / origWidth;
        int newWidth;
        int newHeight;
        if (imRatio > 1.0) {
            newHeight = inputSize;
            newWidth = (int) (newHeight / imRatio);
        } else {
            newWidth = inputSize;
            newHeight = (int) (newWidth * imRatio);
        }
        double detScale = (double) newHeight / origHeight;

        BufferedImage source = (BufferedImage) input.getWrappedImage();
        BufferedImage canvas = new BufferedImage(inputSize, inputSize, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, newWidth, newHeight, null); // top-left; rest of canvas stays black
        g.dispose();

        ctx.setAttachment("origWidth", origWidth);
        ctx.setAttachment("origHeight", origHeight);
        ctx.setAttachment("detScale", detScale);

        // Built via plain pixel loops, NOT NDArray ops (transpose/flip/sub/div/expandDims) - the
        // ONNX Runtime engine's own NDArray implementation (the only engine on the classpath now
        // that PyTorch was removed - see the buffalo_l migration) only supports basic tensor
        // creation/read (`NDManager.create(...)`, `.toFloatArray()`), not general arithmetic;
        // those calls threw `UnsupportedOperationException: This NDArray implementation does not
        // currently support this operation` the moment this ran against the real app with PyTorch
        // actually gone (the earlier smoke test's classpath still had PyTorch on it at the time,
        // which silently absorbed these ops via its own fully-featured NDArray - a gap in that
        // verification, not a second bug). CHW, BGR channel order (swapRB, matches the reference
        // cv2.dnn.blobFromImage(..., swapRB=True) call), (pixel-127.5)/128 per SCRFD's own spec.
        int[] pixels = canvas.getRGB(0, 0, inputSize, inputSize, null, 0, inputSize);
        int planeSize = inputSize * inputSize;
        float[] data = new float[3 * planeSize];
        for (int i = 0; i < planeSize; i++) {
            int rgb = pixels[i];
            float r = (rgb >> 16) & 0xFF;
            float gc = (rgb >> 8) & 0xFF;
            float b = rgb & 0xFF;
            data[i] = (b - 127.5f) / 128f;               // channel 0 = B
            data[planeSize + i] = (gc - 127.5f) / 128f;  // channel 1 = G
            data[2 * planeSize + i] = (r - 127.5f) / 128f; // channel 2 = R
        }
        NDArray array = ctx.getNDManager().create(data, new Shape(1, 3, inputSize, inputSize));
        return new NDList(array);
    }

    /** {@inheritDoc} */
    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        int origWidth = (int) ctx.getAttachment("origWidth");
        int origHeight = (int) ctx.getAttachment("origHeight");
        double detScale = (double) ctx.getAttachment("detScale");

        int fmc = STRIDES.length; // 3 - see class javadoc on det_10g.onnx's 9-output layout
        List<Candidate> candidates = new ArrayList<>();

        for (int strideIdx = 0; strideIdx < fmc; strideIdx++) {
            int stride = STRIDES[strideIdx];
            int featWidth = inputSize / stride;
            int featHeight = inputSize / stride;

            float[] scores = list.get(strideIdx).toFloatArray();
            float[] bboxDists = list.get(strideIdx + fmc).toFloatArray();
            float[] kpsDists = list.get(strideIdx + fmc * 2).toFloatArray();

            int count = featWidth * featHeight * NUM_ANCHORS;
            for (int n = 0; n < count; n++) {
                double score = scores[n];
                if (score < confThresh) {
                    continue;
                }

                // Anchor center for this index: spatial position first (row-major, h outer/w
                // inner), NUM_ANCHORS consecutive copies per position sharing the same center -
                // see class javadoc, this ordering must match the model output's own flattening.
                int spatialIdx = n / NUM_ANCHORS;
                int w = spatialIdx % featWidth;
                int h = spatialIdx / featWidth;
                double cx = w * (double) stride;
                double cy = h * (double) stride;

                // distance2bbox: predicted distances (already *stride, done in one pass below)
                // from the anchor center to each of the 4 box edges.
                double dLeft = bboxDists[n * 4] * stride;
                double dTop = bboxDists[n * 4 + 1] * stride;
                double dRight = bboxDists[n * 4 + 2] * stride;
                double dBottom = bboxDists[n * 4 + 3] * stride;

                double x1 = cx - dLeft;
                double y1 = cy - dTop;
                double x2 = cx + dRight;
                double y2 = cy + dBottom;

                double[] kps = new double[NUM_LANDMARKS * 2];
                for (int j = 0; j < NUM_LANDMARKS; j++) {
                    kps[j * 2] = cx + kpsDists[n * NUM_LANDMARKS * 2 + j * 2] * stride;
                    kps[j * 2 + 1] = cy + kpsDists[n * NUM_LANDMARKS * 2 + j * 2 + 1] * stride;
                }

                // Map back from the letterboxed det-image space to the original image's pixel
                // space (undo detScale - the padding itself needs no correction since it was
                // top-left-anchored, i.e. added no offset).
                candidates.add(new Candidate(
                        x1 / detScale, y1 / detScale, x2 / detScale, y2 / detScale, score, kps, detScale));
            }
        }

        List<Candidate> kept = nms(candidates, nmsThresh);

        List<String> retNames = new ArrayList<>();
        List<Double> retProbs = new ArrayList<>();
        List<BoundingBox> retBB = new ArrayList<>();

        for (Candidate c : kept) {
            // Rectangle stays NORMALIZED (fraction of original image width/height) - matches
            // FaceDetectionService's `bounds.getX() * image.getWidth()` usage.
            double normX = c.x1 / origWidth;
            double normY = c.y1 / origHeight;
            double normW = (c.x2 - c.x1) / origWidth;
            double normH = (c.y2 - c.y1) / origHeight;

            // Landmark points stay in ABSOLUTE pixel space (undo detScale, no width/height
            // division) - matches FaceAligner using them directly with no further scaling.
            List<Point> keyPoints = new ArrayList<>(NUM_LANDMARKS);
            for (int j = 0; j < NUM_LANDMARKS; j++) {
                keyPoints.add(new Point(c.kps[j * 2] / c.detScale, c.kps[j * 2 + 1] / c.detScale));
            }

            retNames.add("Face");
            retProbs.add(c.score);
            retBB.add(new Landmark(normX, normY, normW, normH, keyPoints));
        }

        return new DetectedObjects(retNames, retProbs, retBB);
    }

    // Greedy NMS by descending score, reusing BoundingBox#getIoU (same utility the RetinaFace
    // translator already relies on) rather than hand-rolling IoU math again.
    private static List<Candidate> nms(List<Candidate> candidates, double nmsThresh) {
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble((Candidate c) -> c.score).reversed());

        List<Candidate> kept = new ArrayList<>();
        for (Candidate candidate : sorted) {
            Rectangle candidateRect = new Rectangle(candidate.x1, candidate.y1,
                    candidate.x2 - candidate.x1, candidate.y2 - candidate.y1);
            boolean suppressed = false;
            for (Candidate acceptedCandidate : kept) {
                Rectangle acceptedRect = new Rectangle(acceptedCandidate.x1, acceptedCandidate.y1,
                        acceptedCandidate.x2 - acceptedCandidate.x1, acceptedCandidate.y2 - acceptedCandidate.y1);
                if (candidateRect.getIoU(acceptedRect) > nmsThresh) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) {
                kept.add(candidate);
            }
        }
        return kept;
    }

    // All coordinates here are in det-image (letterboxed, pre-detScale-correction) pixel space
    // except where noted - x1/y1/x2/y2 are stored ALREADY divided by detScale (original-image
    // pixel space) for NMS/rectangle purposes, kps is kept in det-image space (divided by
    // detScale only at final Point construction) purely so detScale doesn't need re-deriving.
    private static final class Candidate {
        final double x1;
        final double y1;
        final double x2;
        final double y2;
        final double score;
        final double[] kps;
        final double detScale;

        Candidate(double x1, double y1, double x2, double y2, double score, double[] kps, double detScale) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.score = score;
            this.kps = kps;
            this.detScale = detScale;
        }
    }
}
