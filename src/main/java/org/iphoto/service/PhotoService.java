package org.iphoto.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.TaskEntity;
import org.iphoto.mappers.PhotoMapper;
import org.iphoto.model.Photo;
import org.iphoto.model.TaskStatus;
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
import java.util.stream.Collectors;

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

            List<TaskEntity> pendingDeletes = taskRepository.findExecutableTasks(TaskStatus.PENDING.name(), LocalDateTime.now().plusYears(1));
            for (TaskEntity task : pendingDeletes) {
                try {
                    Map<String, String> payload = objectMapper.readValue(task.getPayload(), new TypeReference<>() {});
                    if ("PERMANENT_DELETE".equals(task.getTaskType()) && photo.getId() !=null && photo.getId().toString().equals(payload.get("photoId"))) {
                        task.setStatus(TaskStatus.IGNORED);
                        task.setErrorMessage("Asset was restored by user, permanent delete task cancelled.");
                        taskRepository.save(task);
                    }
                } catch (IOException e) {
                    System.err.println("Error parsing task payload for task " + task.getId() + ": " + e.getMessage());
                }
            }
        }
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
            TaskEntity deleteTask = new TaskEntity();
            deleteTask.setTaskType("PERMANENT_DELETE");
            deleteTask.setPayload(objectMapper.writeValueAsString(payload));
            deleteTask.setExecuteAt(LocalDateTime.now().plusDays(30));
            taskRepository.save(deleteTask);
        }
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

    public Map<String, List<Photo>> getDuplicates() {
        List<String> duplicateHashes = photoRepository.findDuplicateHashes();
        List<PhotoEntity> entities = photoRepository.findByFileHashInAndIsGarbageFalse(duplicateHashes);
        return entities.stream().map(photoMapper::photoEntityToPhoto).collect(Collectors.groupingBy(Photo::fileHash));
    }


}
