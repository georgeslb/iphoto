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
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.iphoto.SpringContext;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.PhotoMetadataEntity;
import org.iphoto.entities.TaskEntity;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.UploadStatus;
import org.iphoto.repository.PhotoMetadataRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.TaskRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.iphoto.model.UploadStatus.*;

@Service
@AllArgsConstructor
@Slf4j
public class TaskWorkerManager {

    private final TaskRepository taskRepository;
    private final PhotoRepository photoRepository;
    private final PhotoMetadataRepository metadataRepository;
    private final ThumbnailService thumbnailService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    @Value("${iphoto.storage.photos}")
    private String photoStoragePath;

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
                    switch (handleUploadTask(task)) {
                        case DUPLICATED -> {
                            task.setStatus(TaskStatus.IGNORED);
                            task.setErrorMessage("Duplicate asset detected");
                        }
                        case TRASHED -> {
                            task.setStatus(TaskStatus.TRASHED);
                            task.setErrorMessage("Duplicate asset detected");
                        }
                        default -> task.setStatus(TaskStatus.COMPLETED);
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

    private UploadStatus handleUploadTask(TaskEntity task) throws Exception {
        Map<String, String> payload = objectMapper.readValue(task.getPayload(), new TypeReference<>() {
        });
        File tempFile = new File(payload.get("tempFilePath"));

        if (!tempFile.exists()) throw new RuntimeException("Temp file not found");

        String hash;
        try (FileInputStream fis = new FileInputStream(tempFile)) {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(fis.readAllBytes()));
        }

        return photoRepository.findByFileHash(hash).map(photoEntity -> {
            try {
                Files.deleteIfExists(tempFile.toPath());
                if (photoEntity.getIsGarbage()) {
                    return TRASHED;
                }
                return DUPLICATED;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        }).orElseGet(() -> {
            try {
                return processNewAsset(task, payload, tempFile, hash);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private UploadStatus processNewAsset(TaskEntity task, Map<String, String> payload, File tempFile, String hash) throws IOException {
        String contentType = payload.get("contentType");
        Path storageDir = Paths.get(photoStoragePath);
        if (!Files.exists(storageDir)) Files.createDirectories(storageDir);
        Path finalPath = storageDir.resolve(task.getId() + "_" + payload.get("originalFileName"));
        Files.move(tempFile.toPath(), finalPath);

        String thumbPath = thumbnailService.generateThumbnail(finalPath.toString(), task.getId().toString(), contentType);

        PhotoEntity photo = new PhotoEntity(task.getId(), payload.get("originalFileName"), finalPath.toString(), thumbPath, hash, Files.size(finalPath), contentType);
        photo.setNew(true);

        PhotoMetadataEntity metadataEntity = new PhotoMetadataEntity(photo);
        Map<String, String> rawMetadata = new HashMap<>();

        if (contentType != null) {
            if (contentType.startsWith("image"))
                extractImageMetadata(finalPath.toFile(), photo, metadataEntity, rawMetadata);
            else if (contentType.startsWith("video"))
                extractVideoMetadata(finalPath.toFile(), photo, metadataEntity);
        }

        metadataEntity.setRawMetadataJson(objectMapper.writeValueAsString(rawMetadata));

        photoRepository.save(photo);
        metadataRepository.save(metadataEntity);

        return SUCCESS;
    }

    // This method is now ONLY for the delayed permanent deletion
    private void handlePermanentDelete(TaskEntity task) throws Exception {
        Map<String, String> payload = objectMapper.readValue(task.getPayload(), new TypeReference<>() {
        });
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
                for (Tag tag : directory.getTags()) {
                    raw.put(directory.getName() + ":" + tag.getTagName(), tag.getDescription());
                }
            }
            ExifSubIFDDirectory dateDir = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (dateDir != null) {
                photo.setDateTaken(extractBestCreationDate(file));
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
        } catch (Exception e) {
            //TODO
        }
    }

    private void extractVideoMetadata(File file, PhotoEntity photo, PhotoMetadataEntity meta) {
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
            photo.setDateTaken(extractBestCreationDate(file));
            grabber.stop();
        } catch (Exception e) {
            //TODO
        }
    }


    public static LocalDateTime extractBestCreationDate(File file) {

        try {

            // 1. EXIF image date
            LocalDateTime exifDate = extractExifDate(file);
            if (exifDate != null) {
                return exifDate;
            }

            // 2. Video metadata date
            LocalDateTime videoDate = extractVideoMetadataDate(file);
            if (videoDate != null) {
                return videoDate;
            }

            // 3. Filename date
            LocalDateTime filenameDate =
                    extractDateFromFilename(file.getName());

            if (filenameDate != null) {
                return filenameDate;
            }

            // 4. Filesystem date
            LocalDateTime fsDate = extractFilesystemDate(file);

            if (fsDate != null) {
                return fsDate;
            }

        } catch (Exception ignored) {
            return null;
        }

        // fallback if no date found
        return LocalDateTime.now();
    }

    // ---------------------------------------------------
    // IMAGE EXIF DATE
    // ---------------------------------------------------

    private static LocalDateTime extractExifDate(File file) {
        try {
            Metadata metadata =
                    ImageMetadataReader.readMetadata(file);
            ExifSubIFDDirectory directory =
                    metadata.getFirstDirectoryOfType(
                            ExifSubIFDDirectory.class);
            if (directory == null) {
                return null;
            }
            String dateStr =
                    directory.getString(
                            ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL
                    );
            if (dateStr == null || dateStr.isBlank()) {
                return null;
            }
            // EXIF format:
            // 2024:01:15 14:30:22
            DateTimeFormatter formatter =
                    DateTimeFormatter.ofPattern(
                            "yyyy:MM:dd HH:mm:ss"
                    );

            return LocalDateTime.parse(dateStr, formatter);

        } catch (Exception ignored) {
            return null;
        }
    }

    // ---------------------------------------------------
    // VIDEO METADATA DATE
    // ---------------------------------------------------

    private static LocalDateTime extractVideoMetadataDate(File file) {

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file)) {
            grabber.start();
            // creation_time usually exists here
            String creationTime =
                    grabber.getMetadata("creation_time");
            if (creationTime == null ||
                    creationTime.isBlank()) {
                return null;
            }
            creationTime = creationTime.replace("Z", "").replace("T", " ");

            return LocalDateTime.parse(creationTime, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception ignored) {
            return null;
        }
    }

    // ---------------------------------------------------
    // FILENAME DATE
    // ---------------------------------------------------

    private static LocalDateTime extractDateFromFilename(
            String filename) {

        try {

            List<PatternFormat> patterns = Arrays.asList(

                    // IMG_20240115_143022.jpg
                    new PatternFormat(
                            "(\\d{8})_(\\d{6})",
                            "yyyyMMddHHmmss"
                    ),

                    // 2024-01-15_14-30-22
                    new PatternFormat(
                            "(\\d{4}-\\d{2}-\\d{2})[_ ](\\d{2}-\\d{2}-\\d{2})",
                            "yyyy-MM-ddHH-mm-ss"
                    ),

                    // 20240115143022
                    new PatternFormat(
                            "(\\d{14})",
                            "yyyyMMddHHmmss"
                    ),

                    // 2024-01-15
                    new PatternFormat(
                            "(\\d{4}-\\d{2}-\\d{2})",
                            "yyyy-MM-dd"
                    )
            );

            for (PatternFormat pf : patterns) {

                Matcher matcher =
                        Pattern.compile(pf.regex)
                                .matcher(filename);

                if (matcher.find()) {

                    StringBuilder value =
                            new StringBuilder();

                    for (int i = 1;
                         i <= matcher.groupCount();
                         i++) {

                        value.append(matcher.group(i));
                    }

                    DateTimeFormatter formatter =
                            DateTimeFormatter.ofPattern(
                                    pf.format);

                    if (pf.format.equals("yyyy-MM-dd")) {

                        return LocalDate.parse(
                                value.toString(),
                                formatter
                        ).atStartOfDay();
                    }

                    return LocalDateTime.parse(
                            value.toString(),
                            formatter
                    );
                }
            }

        } catch (Exception ignored) {
            return null;
        }

        return null;
    }

    // ---------------------------------------------------
    // FILESYSTEM DATE
    // ---------------------------------------------------

    private static LocalDateTime extractFilesystemDate(
            File file) {

        try {

            Path path = file.toPath();

            BasicFileAttributes attr =
                    Files.readAttributes(
                            path,
                            BasicFileAttributes.class
                    );

            return LocalDateTime.ofInstant(
                    attr.creationTime().toInstant(),
                    ZoneId.systemDefault()
            );

        } catch (Exception ignored) {
            return null;
        }
    }

    // ---------------------------------------------------
    // HELPER CLASS
    // ---------------------------------------------------

    private static class PatternFormat {

        String regex;
        String format;

        PatternFormat(String regex,
                      String format) {

            this.regex = regex;
            this.format = format;
        }
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
    }
}
