package org.iphoto.service;

import net.coobird.thumbnailator.Thumbnails;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
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
            String filterString = fixRotationArgument(grabber);

            if (filterString != null) {
                grabber.setVideoOption("vf", filterString);
                log.info("Applied FFmpeg filter '{}' for video: {}", filterString, videoPath);
            }

            long totalFrames = grabber.getLengthInFrames();
            grabber.setFrameNumber((int) Math.min(totalFrames / 10, 30));


            BufferedImage bi = converter.convert(grabber.grabImage());

            if (bi != null) {
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

    private static String fixRotationArgument(FFmpegFrameGrabber grabber) {
        double videoRotation = grabber.getDisplayRotation();


        // Apply FFmpeg filters based on rotation metadata
        // Note: FFmpeg filters are applied sequentially.
        // The 'transpose' filter handles rotation and flipping.
        // We need to apply the inverse rotation to get the correct orientation.
        // FFmpeg transpose filter options:
        // 0 = 90CounterCLockwise and Vertical Flip (default)
        // 1 = 90Clockwise
        // 2 = 90CounterCLockwise
        // 3 = 90Clockwise and Vertical Flip
        // We want to rotate the image so it appears upright.
        // If video is 90 CW (rotate=90), we need to apply transpose=1 (90 CW) to make it upright.
        // If video is 270 CW (rotate=270), we need to apply transpose=2 (90 CCW) to make it upright.
        // If video is 180 CW (rotate=180), we need to apply vflip,hflip.

        if (videoRotation == 90) { // Video is 90 deg clockwise, need to rotate 90 deg clockwise to make it upright
            return "transpose=1";
        } else if (videoRotation == 270 || videoRotation == -90) { // Video is 270 deg clockwise, need to rotate 90 deg counter-clockwise to make it upright
            return "transpose=2";
        } else if (videoRotation == 180 || videoRotation == -180) { // Video is 180 deg
            return "vflip,hflip"; // Flip horizontally and vertically
        }
        return null;
    }
}
