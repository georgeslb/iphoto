package org.iphoto.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.TaskEntity;
import org.iphoto.mappers.PhotoMapper;
import org.iphoto.model.Photo;
import org.iphoto.model.PhotoEditRequest;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;
import org.iphoto.properties.IPhotoProperties;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.TaskRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

@Service
@AllArgsConstructor
@Slf4j
public class PhotoService {

    private final PhotoRepository photoRepository;
    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper;
    private final PhotoMapper photoMapper;
    private final AlbumService albumService;
    private final PhotoDeletionService photoDeletionService;
    private final TaskManagerService taskManagerService;
    private final DuplicationService duplicationService;
    private final ThumbnailService thumbnailService;
    private final IPhotoProperties iPhotoProperties;


    public Page<Photo> getPhotos(Pageable pageable) {
        return photoRepository.findByIsGarbageFalse(pageable).map(photoMapper::photoEntityToPhoto);
    }

    public Page<Photo> getGarbagePhotos(Pageable pageable) {
        return photoRepository.findByIsGarbageTrue(pageable).map(photoMapper::photoEntityToPhoto);
    }

    @Transactional
    public void restoreAssetsFromGarbage(List<UUID> ids) {
        List<PhotoEntity> photos = photoRepository.findAllById(ids);
        for (PhotoEntity photo : photos) {

            if (photo == null || !photo.getIsGarbage()) continue;

            photo.setIsGarbage(false);
            photo.setGarbageAt(null);
            photoRepository.save(photo);

            List<TaskEntity> pendingDeletes = taskRepository.findExecutableTasks(TaskStatus.PENDING.name(), LocalDateTime.now().plusYears(1), ids);
            for (TaskEntity task : pendingDeletes) {
                if ("PERMANENT_DELETE".equals(task.getTaskType()) && photo.getId() != null ) {
                    task.setStatus(TaskStatus.IGNORED);
                    task.setErrorMessage("Asset was restored by user, permanent delete task cancelled.");
                    taskManagerService.updateTask(task);
                }
            }
        }
        duplicationService.restoreFromGarbage(photos);
    }

    @Transactional
    public void moveAssetsToGarbage(List<UUID> ids) throws Exception {
        List<PhotoEntity> photos = photoRepository.findAllById(ids);
        for (PhotoEntity photo : photos) {
            if (photo == null || photo.getIsGarbage()) continue;

            photo.setIsGarbage(true);
            photo.setGarbageAt(LocalDateTime.now());
            photoRepository.save(photo);

            Map<String, String> payload = Map.of("photoId", photo.getId().toString());
            taskManagerService
                    .createTaskEntity(TaskType.PERMANENT_DELETE, photo.getId(), objectMapper.writeValueAsString(payload), LocalDateTime.now().plusDays(30));
        }
        duplicationService.processMovingToGarbage(photos);
        photos.stream().map(PhotoEntity::getAlbums).flatMap(Collection::stream)
                .forEach(album -> albumService.removePhotosFromAlbum(album.getId(), ids));
    }

    @Transactional
    public void permanentDeleteAssets(List<UUID> ids) {
        ids.parallelStream().forEach(photoId -> {
            try {
                Optional<PhotoEntity> optionalPhoto = photoRepository.findById(photoId);
                if (optionalPhoto.isPresent()) {
                    String originalPath = optionalPhoto.get().getOriginalPath();
                    String  thumbnailPath = optionalPhoto.get().getThumbnailPath();
                    photoDeletionService.permanentDeleteAssets(photoId);
                    photoRepository.findById(photoId).or(() -> {
                        try {
                            Files.deleteIfExists(Paths.get(originalPath));
                            if (thumbnailPath != null) {
                                Files.deleteIfExists(Paths.get(thumbnailPath));
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        return Optional.empty();
                    });

                }

            } catch (IOException e) {
                log.error("can't delete photo with id {}", photoId, e);
            }
        });

    }

    @Transactional
    public Photo updatePhotoDate(UUID id, LocalDateTime newDate) {
        PhotoEntity photoEntity = photoRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Photo not found with ID: " + id));
        photoEntity.setDateTaken(newDate);
        photoRepository.save(photoEntity);
        return photoMapper.photoEntityToPhoto(photoEntity);
    }

    @Transactional
    public Photo editPhoto(UUID id, PhotoEditRequest request) throws Exception {
        PhotoEntity photoEntity = photoRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Photo not found with ID: " + id));

        String originalPath = photoEntity.getOriginalPath();
        if (originalPath == null || !Files.exists(Paths.get(originalPath))) {
            throw new IOException("Original file not found");
        }

        org.bytedeco.opencv.opencv_core.Mat image = org.bytedeco.opencv.global.opencv_imgcodecs.imread(originalPath);
        if (image.empty()) {
            throw new IOException("Failed to load image for editing");
        }

        // Apply rotation first
        if (request.rotation() != 0) {
            org.bytedeco.opencv.opencv_core.Mat rotated = new org.bytedeco.opencv.opencv_core.Mat();
            if (request.rotation() == 90) {
                org.bytedeco.opencv.global.opencv_core.rotate(image, rotated, org.bytedeco.opencv.global.opencv_core.ROTATE_90_CLOCKWISE);
            } else if (request.rotation() == 180) {
                org.bytedeco.opencv.global.opencv_core.rotate(image, rotated, org.bytedeco.opencv.global.opencv_core.ROTATE_180);
            } else if (request.rotation() == 270 || request.rotation() == -90) {
                org.bytedeco.opencv.global.opencv_core.rotate(image, rotated, org.bytedeco.opencv.global.opencv_core.ROTATE_90_COUNTERCLOCKWISE);
            } else {
                 rotated = image;
            }
            image = rotated;
        }

        // Then apply crop
        if (request.width() > 0 && request.height() > 0) {
            org.bytedeco.opencv.opencv_core.Rect roi = new org.bytedeco.opencv.opencv_core.Rect(request.cropX(), request.cropY(), request.width(), request.height());
            image = new org.bytedeco.opencv.opencv_core.Mat(image, roi);
        }

        if (request.saveAsNew()) {
            String tempPath = iPhotoProperties.getStorage().getTemp() + "/" + UUID.randomUUID() + ".jpg";
            // Use imencode to ensure correct extension handling
            String ext = ".jpg";
            if (photoEntity.getContentType() != null && photoEntity.getContentType().toLowerCase().contains("png")) {
                ext = ".png";
            }
            try (org.bytedeco.javacpp.BytePointer buf = new org.bytedeco.javacpp.BytePointer()) {
                org.bytedeco.opencv.global.opencv_imgcodecs.imencode(ext, image, buf);
                byte[] bytes = new byte[(int) buf.limit()];
                buf.get(bytes);
                Files.write(Paths.get(tempPath), bytes);
            }
            
            Map<String, String> payload = new HashMap<>();
            payload.put("tempFilePath", tempPath);
            payload.put("originalFileName", photoEntity.getFileName());
            payload.put("contentType", photoEntity.getContentType());
            payload.put("fileSize", String.valueOf(Files.size(Paths.get(tempPath))));
            
            taskManagerService.createTaskEntity(TaskType.PROCESS_UPLOAD, UUID.randomUUID(), objectMapper.writeValueAsString(payload), null);
            return photoMapper.photoEntityToPhoto(photoEntity);
        } else {
            // Re-implement imencode fix for overwriting original
            String ext = ".jpg";
            if (photoEntity.getContentType() != null && photoEntity.getContentType().toLowerCase().contains("png")) {
                ext = ".png";
            }
            
            try (org.bytedeco.javacpp.BytePointer buf = new org.bytedeco.javacpp.BytePointer()) {
                org.bytedeco.opencv.global.opencv_imgcodecs.imencode(ext, image, buf);
                byte[] bytes = new byte[(int) buf.limit()];
                buf.get(bytes);
                Files.write(Paths.get(originalPath), bytes);
            }

            thumbnailService.generateThumbnail(originalPath, photoEntity.getId().toString(), photoEntity.getContentType());
            
            photoEntity.setFileSize(Files.size(Paths.get(originalPath)));
            photoEntity.setWidth(image.cols());
            photoEntity.setHeight(image.rows());
            photoRepository.save(photoEntity);
            
            return photoMapper.photoEntityToPhoto(photoEntity);
        }
    }
}
