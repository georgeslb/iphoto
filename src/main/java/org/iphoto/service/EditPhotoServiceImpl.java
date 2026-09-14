package org.iphoto.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.TaskEntity;
import org.iphoto.entities.UserEntity; // Import UserEntity
import org.iphoto.exception.AccessDeniedForPhotoChangesException;
import org.iphoto.model.request.PhotoEditRequest;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;
import org.iphoto.properties.IPhotoProperties;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PhotoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@AllArgsConstructor
@Slf4j
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class EditPhotoServiceImpl implements EditPhotoService {

    private PhotoRepository photoRepository;
    private ObjectMapper objectMapper;
    private TaskManagerService taskManagerService;
    private ThumbnailService thumbnailService;
    private IPhotoProperties iPhotoProperties;
    private FaceRepository faceRepository;
    private DuplicationService duplicationService;
    private TaskMapper taskMapper;
    private PersonRecognitionService personRecognitionService;
    private SpaceService spaceService;

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PhotoEntity editImage(
            UserEntity user, // Added UserEntity parameter
            UUID id,
            MultipartFile file,
            PhotoEditRequest request
    ) throws IOException {

        // =========================
        // LOAD IMAGE
        // =========================
        PhotoEntity photoEntity = photoRepository.findById(id) // Filter by user
                .orElseThrow(() -> new NoSuchElementException("Photo not found with ID: " + id));

        if (!photoEntity.getSpace().getUser().getId().equals(user.getId())) {
            throw new AccessDeniedForPhotoChangesException();
        }
        spaceService.assertSyncWritable(photoEntity.getSpace());
        String originalImagePath = photoEntity.getOriginalPath();

        File inputFile = new File(originalImagePath);

        if (!inputFile.exists()) {
            throw new RuntimeException("Existing Image not found.");
        }

        // =========================
        // LOAD ORIGINAL
        // =========================

        BufferedImage originalImage = ImageIO.read(inputFile);

        if (originalImage == null) {
            throw new RuntimeException("Failed to read image.");
        }



        // =========================
        // DETERMINE OUTPUT PATH
        // =========================

        String outputPath;

        NewModifiedPhotoData newModifiedPhotoData = null;
        if (request.saveAsNew()) {
            newModifiedPhotoData = generateNewFileName(originalImagePath);
            outputPath = newModifiedPhotoData.getTmpPath();
        } else {
            outputPath = originalImagePath;
        }
        Path dest = Path.of(outputPath);
        file.transferTo(dest);
        // =========================
        // SAVE WITH HIGH QUALITY
        // =========================


        if (dest.toFile().exists()) {
            return updatePhotoInDatabase(file, request.saveAsNew(), newModifiedPhotoData, photoEntity); // Pass user
        }


        return null;
    }

    private PhotoEntity updatePhotoInDatabase(MultipartFile file, boolean saveNew, NewModifiedPhotoData newModifiedPhotoData, PhotoEntity originalPhoto) { // Added UserEntity parameter
        if (saveNew) {

            Map<String, String> payload = new HashMap<>();
            try {
                payload.put("tempFilePath", newModifiedPhotoData.getTmpPath());
                payload.put("originalFileName", newModifiedPhotoData.getFileName());
                payload.put("contentType", file.getContentType());
                payload.put("fileSize", String.valueOf(Files.size(Path.of(newModifiedPhotoData.getTmpPath()))));
                payload.put("userId", originalPhoto.getSpace().getUser().getId().toString()); // Add user ID to payload
                UUID newId = UUID.randomUUID();
                TaskEntity task = taskManagerService.createTaskEntityImmediately(TaskType.PROCESS_UPLOAD, newId, objectMapper.writeValueAsString(payload), null);
                while (taskManagerService.getTask(task.getId()).isEmpty()
                        || taskManagerService.getTask(task.getId()).get().getStatus() == TaskStatus.PENDING
                        || photoRepository.findById(newId).isEmpty()) {
                    Thread.sleep(500);
                }
                return photoRepository.findById(newId).get();
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                throw new RuntimeException(e);
            }
        } else {
            // overwrite original
            try {
                BufferedImage workingImage = ImageIO.read(file.getInputStream());
                thumbnailService.regenerateThumbnailInPlace(originalPhoto.getOriginalPath(), originalPhoto.getId().toString(), file.getContentType(), originalPhoto.getThumbnailPath());
                originalPhoto.setFileHash(HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(file.getInputStream().readAllBytes())));
                originalPhoto.setFileSize(Files.size(Path.of(originalPhoto.getOriginalPath())));
                originalPhoto.setWidth(workingImage.getWidth());
                originalPhoto.setHeight(workingImage.getHeight());
                // Must happen before the deleteAll below - removeFaceFromBucket still needs each
                // face's own embedding to subtract it back out of its person's bucket (see
                // FaceService.deleteFace, same requirement, same fix).
                originalPhoto.getFaces().forEach(face -> {
                    if (face.getPerson() != null) {
                        personRecognitionService.removeFaceFromBucket(face.getPerson(), face);
                    }
                });
                faceRepository.deleteAll(originalPhoto.getFaces());
                duplicationService.deleteDuplicatesForPhoto(originalPhoto);
                taskMapper.updateTasksToAction(TaskMapper.Action.ON_EDIT_PHOTO_ACTION, originalPhoto.getId());
                return photoRepository.save(originalPhoto);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * High quality rotation preserving dimensions.
     */
    private BufferedImage rotateImage(
            BufferedImage image,
            double angle
    ) {

        double radians = Math.toRadians(angle);

        double sin = Math.abs(Math.sin(radians));
        double cos = Math.abs(Math.cos(radians));

        int originalWidth = image.getWidth();
        int originalHeight = image.getHeight();

        int newWidth = (int) Math.floor(
                originalWidth * cos + originalHeight * sin
        );

        int newHeight = (int) Math.floor(
                originalHeight * cos + originalWidth * sin
        );

        BufferedImage rotated = new BufferedImage(
                newWidth,
                newHeight,
                BufferedImage.TYPE_INT_RGB
        );

        Graphics2D g2d = rotated.createGraphics();

        // =========================
        // HIGH QUALITY SETTINGS
        // =========================

        g2d.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC
        );

        g2d.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY
        );

        g2d.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );

        AffineTransform transform = new AffineTransform();

        transform.translate(
                (newWidth - originalWidth) / 2.0,
                (newHeight - originalHeight) / 2.0
        );

        transform.rotate(
                radians,
                originalWidth / 2.0,
                originalHeight / 2.0
        );

        g2d.drawRenderedImage(image, transform);

        g2d.dispose();

        return rotated;
    }

    /**
     * Prevent invalid crop dimensions.
     */
    private Rectangle adjustCropArea(
            int x,
            int y,
            int width,
            int height,
            BufferedImage image
    ) {

        x = Math.max(0, x);
        y = Math.max(0, y);

        width = Math.min(
                width,
                image.getWidth() - x
        );

        height = Math.min(
                height,
                image.getHeight() - y
        );

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Invalid crop area."
            );
        }

        return new Rectangle(
                x,
                y,
                width,
                height
        );
    }

    /**
     * Save image with max JPEG quality.
     */
    private void saveHighQualityImage(
            BufferedImage image,
            String outputPath
    ) throws IOException {

        String format = getFileExtension(outputPath);

        File outputFile = new File(outputPath);

        // PNG = lossless
        if ("png".equalsIgnoreCase(format)) {

            ImageIO.write(
                    image,
                    "png",
                    outputFile
            );

            return;
        }

        // JPEG high quality
        Iterator<ImageWriter> writers =
                ImageIO.getImageWritersByFormatName("jpg");

        if (!writers.hasNext()) {
            throw new RuntimeException(
                    "No JPEG writer available."
            );
        }

        ImageWriter writer = writers.next();

        ImageWriteParam params =
                writer.getDefaultWriteParam();

        params.setCompressionMode(
                ImageWriteParam.MODE_EXPLICIT
        );

        params.setCompressionQuality(1.0f);

        try (ImageOutputStream ios =
                     ImageIO.createImageOutputStream(outputFile)) {

            writer.setOutput(ios);

            writer.write(
                    null,
                    new IIOImage(image, null, null),
                    params
            );

        } finally {
            writer.dispose();
        }
    }

    private String getFileExtension(
            String path
    ) {

        int dotIndex = path.lastIndexOf(".");

        if (dotIndex == -1) {
            return "jpg";
        }

        return path.substring(dotIndex + 1);
    }


    /**
     * Generates:
     * image_edited_<uuid>.jpg
     */
    private NewModifiedPhotoData generateNewFileName(String originalPathStr) throws IOException {

        NewModifiedPhotoData newModifiedPhotoData = new NewModifiedPhotoData();

        Path originalPath = Paths.get(originalPathStr);

        String fileName = originalPath.getFileName().toString();

        int dotIndex = fileName.lastIndexOf(".");

        String name = dotIndex > 0
                ? fileName.substring(0, dotIndex)
                : fileName;

        String extension = dotIndex > 0
                ? fileName.substring(dotIndex)
                : ".jpg";

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
        String newFileName =
                name
                        + "_edited_"
                        + formatter.format(LocalDateTime.now())
                        + extension;
        newModifiedPhotoData.setFileName( newFileName);

        Path path = Paths.get(iPhotoProperties.getStorage().getTemp(), UUID.randomUUID() + extension);

        newModifiedPhotoData.setTmpPath( path.toString());

        return newModifiedPhotoData;
    }


    @Data
    private static class NewModifiedPhotoData {
        String tmpPath;
        String fileName;
        String existingFile;
    }
}
