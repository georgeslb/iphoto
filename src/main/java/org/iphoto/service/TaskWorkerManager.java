package org.iphoto.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.iphoto.SpringContext;
import org.iphoto.model.PhotoEntity;
import org.iphoto.model.PhotoMetadataEntity;
import org.iphoto.model.TaskEntity;
import org.iphoto.model.TaskStatus;
import org.iphoto.repository.PhotoMetadataRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class TaskWorkerManager {
    private static final Logger log = LoggerFactory.getLogger(TaskWorkerManager.class);

    private final TaskRepository taskRepository;
    private final PhotoRepository photoRepository;
    private final PhotoMetadataRepository metadataRepository;
    private final ThumbnailService thumbnailService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    @Value("${iphoto.storage.photos}")
    private String photoStoragePath;

    public TaskWorkerManager(TaskRepository taskRepository, 
                             PhotoRepository photoRepository, 
                             PhotoMetadataRepository metadataRepository, ThumbnailService thumbnailService, 
                             ObjectMapper objectMapper, PlatformTransactionManager transactionManager, EntityManager entityManager) {
        this.taskRepository = taskRepository;
        this.photoRepository = photoRepository;
        this.metadataRepository = metadataRepository;
        this.thumbnailService = thumbnailService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelay = 5000)
    public void pollPendingTasks() {
        List<UUID> taskIdsToProcess = transactionTemplate.execute(status -> taskRepository.findExecutableTasks(TaskStatus.PENDING, LocalDateTime.now())
                .stream().map(task -> {
                    task.setStatus(TaskStatus.PROCESSING);
                    taskRepository.save(task);
                    return task.getId();
                }).toList());

        if (taskIdsToProcess != null) {
            for (UUID taskId : taskIdsToProcess) {
                executorService.submit(() -> processTask(taskId));
            }
        }
    }

    private void processTask(UUID taskId) {
        transactionTemplate.executeWithoutResult(status -> {
            TaskEntity task = taskRepository.findById(taskId).orElse(null);
            if (task == null) return;
            try {
                if ("PROCESS_UPLOAD".equals(task.getTaskType())) {
                    boolean isDuplicate = handleUploadTask(task);
                    if (isDuplicate) {
                        task.setStatus(TaskStatus.IGNORED);
                        task.setErrorMessage("Duplicate asset detected");
                    } else {
                        task.setStatus(TaskStatus.COMPLETED);
                    }
                } else if ("PERMANENT_DELETE".equals(task.getTaskType())) {
                    handlePermanentDelete(task); // This is now the delayed permanent delete
                    task.setStatus(TaskStatus.COMPLETED);
                }
            } catch (Exception e) {
                log.error("Task failed", e);
                task.setStatus(TaskStatus.FAILED);
                task.setErrorMessage(e.getMessage());
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                task.setStackTrace(sw.toString());
            }
            taskRepository.save(task);
        });
    }

    private boolean handleUploadTask(TaskEntity task) throws Exception {
        Map<String, String> payload = objectMapper.readValue(task.getPayload(), new TypeReference<>() {});
        File tempFile = new File(payload.get("tempFilePath"));
        String contentType = payload.get("contentType");
        if (!tempFile.exists()) throw new RuntimeException("Temp file not found");

        String hash;
        try (FileInputStream fis = new FileInputStream(tempFile)) {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(fis.readAllBytes()));
        }

        if (photoRepository.findByFileHash(hash).isPresent()) {
            Files.deleteIfExists(tempFile.toPath());
            return true;
        }

        Path storageDir = Paths.get(photoStoragePath);
        if (!Files.exists(storageDir)) Files.createDirectories(storageDir);
        Path finalPath = storageDir.resolve(task.getId() + "_" + payload.get("originalFileName"));
        Files.move(tempFile.toPath(), finalPath);

        String thumbPath = thumbnailService.generateThumbnail(finalPath.toString(), task.getId().toString(), contentType);

        PhotoEntity photo = new PhotoEntity(task.getId(), payload.get("originalFileName"), finalPath.toString(), thumbPath, hash, Files.size(finalPath), contentType);
        photo.setDateTaken(LocalDateTime.now());
        photo.setNew(true);

        PhotoMetadataEntity metadataEntity = new PhotoMetadataEntity(photo);
        Map<String, String> rawMetadata = new HashMap<>();

        if (contentType != null) {
            if (contentType.startsWith("image")) extractImageMetadata(finalPath.toFile(), photo, metadataEntity, rawMetadata);
            else if (contentType.startsWith("video")) extractVideoMetadata(finalPath.toFile(), photo, metadataEntity, rawMetadata);
        }

        metadataEntity.setRawMetadataJson(objectMapper.writeValueAsString(rawMetadata));
        
        photoRepository.save(photo);
        metadataRepository.save(metadataEntity);
        
        return false;
    }

    // This method is now ONLY for the delayed permanent deletion
    private void handlePermanentDelete(TaskEntity task) throws Exception {
        Map<String, String> payload = objectMapper.readValue(task.getPayload(), new TypeReference<>() {});
        UUID photoId = UUID.fromString(payload.get("photoId"));
        
        // Use PhotoService to perform the actual deletion logic
        // This ensures consistency and proper cleanup
        // Note: PhotoService.permanentDeleteAssets expects a list, so wrap it
        PhotoService photoService = (PhotoService) SpringContext.getBean("photoService"); // Access PhotoService via SpringContext
        photoService.permanentDeleteAssets(List.of(photoId));
        log.info("Asset {} permanently removed by scheduled task.", photoId);
    }

    private void extractImageMetadata(File file, PhotoEntity photo, PhotoMetadataEntity meta, Map<String, String> raw) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(file);
            for (Directory directory : metadata.getDirectories()) {
                for (Tag tag : directory.getTags()) { raw.put(directory.getName() + ":" + tag.getTagName(), tag.getDescription()); }
            }
            ExifSubIFDDirectory dateDir = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (dateDir != null) {
                if (dateDir.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL) != null) {
                    photo.setDateTaken(LocalDateTime.ofInstant(dateDir.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL).toInstant(), ZoneId.systemDefault()));
                }
                meta.setAperture(dateDir.getString(ExifSubIFDDirectory.TAG_FNUMBER));
                meta.setShutterSpeed(dateDir.getString(ExifSubIFDDirectory.TAG_EXPOSURE_TIME));
                meta.setIso(dateDir.getString(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT));
                meta.setFocalLength(dateDir.getString(ExifSubIFDDirectory.TAG_FOCAL_LENGTH));
            }
            GpsDirectory gpsDir = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            if (gpsDir != null && gpsDir.getGeoLocation() != null) {
                photo.setLocation(gpsDir.getGeoLocation().toDMSString());
                meta.setLatitude(gpsDir.getGeoLocation().getLatitude());
                meta.setLongitude(gpsDir.getGeoLocation().getLongitude());
            }
            JpegDirectory jpegDir = metadata.getFirstDirectoryOfType(JpegDirectory.class);
            if (jpegDir != null) {
                photo.setWidth(jpegDir.getImageWidth());
                photo.setHeight(jpegDir.getImageHeight());
            }
        } catch (Exception e) {}
    }

    private void extractVideoMetadata(File file, PhotoEntity photo, PhotoMetadataEntity meta, Map<String, String> raw) {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file)) {
            grabber.start();
            double videoRotation = grabber.getDisplayRotation();
            photo.setWidth(grabber.getImageWidth());
            photo.setHeight(grabber.getImageHeight());
            if (Math.abs(videoRotation) == 90 || Math.abs(videoRotation) == 270) {
                photo.setWidth(grabber.getImageHeight());
                photo.setHeight(grabber.getImageWidth());
            }
            photo.setDuration(grabber.getLengthInTime() / 1000);
            meta.setCodec(grabber.getVideoCodecName());
            String creationTime = grabber.getMetadata().get("creation_time");
            if (creationTime != null) {
                try {
                   String normalized = creationTime.replace("Z", "").replace("T", " ");
                   photo.setDateTaken(LocalDateTime.parse(normalized, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                } catch (Exception e) {}
            }
            grabber.stop();
        } catch (Exception e) {}
    }

    @PreDestroy
    public void shutdown() { executorService.shutdown(); }
}
