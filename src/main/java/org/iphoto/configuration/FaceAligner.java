package org.iphoto.configuration;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.Landmark;
import ai.djl.modality.cv.output.Point;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;

public final class FaceAligner {

    private FaceAligner() {}

    public static Image align(Image image, Landmark landmark) {
        Iterable<Point> points = landmark.getPath();

        Point leftEye = points.iterator().next();
        Point rightEye = points.iterator().next();

        double leftX = leftEye.getX();
        double leftY = leftEye.getY();

        double rightX = rightEye.getX();
        double rightY = rightEye.getY();

        double angle = Math.atan2(rightY - leftY, rightX - leftX);

        BufferedImage source =
                (BufferedImage) image.getWrappedImage();

        AffineTransform tx = new AffineTransform();

        double centerX = (leftX + rightX) / 2.0;
        double centerY = (leftY + rightY) / 2.0;

        tx.rotate(-angle, centerX, centerY);

        AffineTransformOp op =
                new AffineTransformOp(
                        tx,
                        AffineTransformOp.TYPE_BILINEAR);

        BufferedImage rotated =
                op.filter(source, null);

        double minX = landmark.getBounds().getX() * image.getWidth();
        double minY = landmark.getBounds().getY() * image.getHeight();
        double width = landmark.getBounds().getWidth() * image.getWidth();
        double height = landmark.getBounds().getHeight() * image.getHeight();

        // Add 20% padding
        int padX = (int) (width * 0.2);
        int padY = (int) (height * 0.2);

        int x = Math.max(0, (int) minX - padX);
        int y = Math.max(0, (int) minY - padY);

        int w = Math.min(
                rotated.getWidth() - x,
                (int) width + 2 * padX);

        int h = Math.min(
                rotated.getHeight() - y,
                (int) height + 2 * padY);

        BufferedImage crop =
                rotated.getSubimage(x, y, w, h);

        BufferedImage resized =
                new BufferedImage(
                        112,
                        112,
                        BufferedImage.TYPE_INT_RGB);

        Graphics2D g = resized.createGraphics();

        g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        g.drawImage(crop, 0, 0, 112, 112, null);
        g.dispose();

        return ImageFactory.getInstance().fromImage(resized);
    }
}