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

    private String generateVideoThumbnail(String videoPath, File thumbnailFile) throws IOException {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath)) {
            // CRITICAL FIX: Tell FFmpeg to automatically apply rotation metadata
            grabber.setOption("autorotate", "1"); 
            grabber.start();
            
            // Grab a frame at 10% of the video duration or 1st second
            long totalFrames = grabber.getLengthInFrames();
            grabber.setFrameNumber((int) Math.min(totalFrames / 10, 30));

            Java2DFrameConverter converter = new Java2DFrameConverter();
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
}
