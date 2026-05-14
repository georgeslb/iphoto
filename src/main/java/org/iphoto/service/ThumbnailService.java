package org.iphoto.service;

import net.coobird.thumbnailator.Thumbnails;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class ThumbnailService {
    private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);

    @Value("${iphoto.storage.thumbnails}")
    private String thumbnailStoragePath;

    public String generateThumbnail(String originalPath, String fileId, String contentType) throws IOException {
        Path storagePath = Paths.get(thumbnailStoragePath);
        if (!Files.exists(storagePath)) {
            Files.createDirectories(storagePath);
        }

        String thumbnailFileName = fileId + "_thumb.jpg";
        File thumbnailFile = storagePath.resolve(thumbnailFileName).toFile();

        if (contentType != null && contentType.startsWith("video")) {
            return generateVideoThumbnail(originalPath, thumbnailFile);
        } else {
            return generateImageThumbnail(originalPath, thumbnailFile);
        }
    }

    private String generateImageThumbnail(String originalPath, File thumbnailFile) throws IOException {
        Thumbnails.of(new File(originalPath))
                .size(400, 400)
                .outputFormat("jpg")
                .toFile(thumbnailFile);
        return thumbnailFile.getAbsolutePath();
    }

    private String generateVideoThumbnail(String videoPath, File thumbnailFile) {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.start(); // Start first to read metadata
            long totalFrames = grabber.getLengthInFrames();
            grabber.setFrameNumber((int) Math.min(totalFrames / 10, 30));


            BufferedImage bi = converter.convert(grabber.grabImage());
            double rotate = grabber.getDisplayRotation();
            int angle = (int) rotate;
// save thumbnail
            if (bi != null) {
                bi = rotateImage(bi, -angle);
                ImageIO.write(bi, "jpg", thumbnailFile);
                grabber.stop();
                return thumbnailFile.getAbsolutePath();
            }

            grabber.stop();
            return null;
        } catch (Exception e) {
            log.error("Native video thumbnail generation failed for {}: {}", videoPath, e.getMessage());
            return null;
        }
    }

    private static BufferedImage rotateImage(BufferedImage img, int angle) {
        double rads = Math.toRadians(angle);

        double sin = Math.abs(Math.sin(rads));
        double cos = Math.abs(Math.cos(rads));

        int w = img.getWidth();
        int h = img.getHeight();

        int newWidth = (int) Math.floor(w * cos + h * sin);
        int newHeight = (int) Math.floor(h * cos + w * sin);

        BufferedImage rotated = new BufferedImage(
                newWidth,
                newHeight,
                img.getType()
        );

        Graphics2D g2d = rotated.createGraphics();

        AffineTransform at = new AffineTransform();

        at.translate((double) (newWidth - w) / 2, (double) (newHeight - h) / 2);
        at.rotate(rads, w / 2.0, h / 2.0);

        g2d.setTransform(at);
        g2d.drawImage(img, 0, 0, null);
        g2d.dispose();

        return rotated;
    }
}
