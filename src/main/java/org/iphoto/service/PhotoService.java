package org.iphoto.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.TaskEntity;
import org.iphoto.entities.UserEntity; // Import UserEntity
import org.iphoto.mappers.PhotoMapper;
import org.iphoto.model.*;
import org.iphoto.repository.AlbumRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.TaskRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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
    private final PhotoMapper photoMapper;
    private final AlbumService albumService;
    private final PhotoDeletionService photoDeletionService;
    private final TaskManagerService taskManagerService;
    private final DuplicationService duplicationService;
    private final EditPhotoService editPhotoService;
    private final AlbumRepository albumRepository;

    public Page<Photo> getPhotos(UserEntity user, Pageable pageable) {
        return photoRepository.findByUserAndIsGarbageFalse(user, pageable).map(photoMapper::photoEntityToPhoto);
    }

    public Page<Photo> getGarbagePhotos(UserEntity user, Pageable pageable) {
        return photoRepository.findByUserAndIsGarbageTrue(user, pageable).map(photoMapper::photoEntityToPhoto);
    }

    @Transactional
    public void restoreAssetsFromGarbage(UserEntity user, List<UUID> ids) {
        List<PhotoEntity> photos = ids.stream()
                .map(id -> photoRepository.findByIdAndUser(id, user))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        for (PhotoEntity photo : photos) {
            if (photo == null || !photo.getIsGarbage()) continue;

            photo.setIsGarbage(false);
            photo.setGarbageAt(null);
            photoRepository.save(photo);

            List<TaskEntity> pendingDeletes = taskRepository.findExecutableTasks(TaskStatus.PENDING.name(), LocalDateTime.now().plusYears(1), Collections.singletonList(photo.getId()));
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
    public void moveAssetsToGarbage(UserEntity user, List<UUID> ids) {

        Set<PhotoEntity> photos = photoRepository.findByIdInAndUser(ids, user);

        for (PhotoEntity photo : photos) {
            if (photo == null || photo.getIsGarbage()) continue;

            photo.setIsGarbage(true);
            photo.setGarbageAt(LocalDateTime.now());
            photoRepository.save(photo);

            taskManagerService
                    .createTaskEntity(TaskType.PERMANENT_DELETE, photo.getId(), null, LocalDateTime.now().plusDays(30));
        }
        duplicationService.processMovingToGarbage(photos);
        photos.stream().map(PhotoEntity::getAlbums).flatMap(Collection::stream)
                .forEach(album -> albumService.removePhotosFromAlbum(user, album.getId(), ids)); // Pass user to albumService
    }

    @Transactional
    public  void permanentDeleteAssets(List<UUID> photoId) {
        Set<PhotoEntity> photos = photoRepository.findByIdIn(photoId);
        deleteAssets(photos);
    }

    @Transactional
    public void permanentDeleteAssets(UserEntity user, List<UUID> ids) {
        Set<PhotoEntity> photos = photoRepository.findByIdInAndUser(ids, user);
        deleteAssets(photos);

    }

    private void deleteAssets(Set<PhotoEntity> photos) {
        photos.parallelStream().forEach(photo -> {
            try {
                    String originalPath = photo.getOriginalPath();
                    String  thumbnailPath = photo.getThumbnailPath();
                    photoDeletionService.permanentDeleteAssets(photo); // Pass user to photoDeletionService
                    try {
                        Files.deleteIfExists(Paths.get(originalPath));
                        if (thumbnailPath != null) {
                            Files.deleteIfExists(Paths.get(thumbnailPath));
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
            } catch (Exception e) {
                log.error("can't delete photo with id {}", photo.getId(), e);
            }
        });
    }

    @Transactional
    public Photo updatePhotoDate(UserEntity user, UUID id, LocalDateTime newDate) {
        PhotoEntity photoEntity = photoRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new NoSuchElementException("Photo not found with ID: " + id));
        photoEntity.setDateTaken(newDate);
        photoRepository.save(photoEntity);
        return photoMapper.photoEntityToPhoto(photoEntity);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PhotoEntity fetchNewPhotoFromDatabaseAsync(UserEntity user, UUID id) {
        return photoRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new NoSuchElementException("Photo not found with ID: " + id));
    }
    /**
     * Returns final saved image path.
     */
    @Transactional
    public Photo editPhoto(
            UserEntity user,
            UUID id,
            MultipartFile file, PhotoEditRequest request) {
        try {
            return photoMapper.photoEntityToPhoto(editPhotoService.editImage(user, id, file, request)); // Pass user to editPhotoService
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public List<AlbumDTO> getAlbumsByPhoto(UserEntity user, UUID photoId) {
       return albumRepository.findByUserAndPhotos_Id(user, photoId).stream().map(album -> albumService.convertToDto(user, album)).toList(); // Pass user to albumService
    }

    public Page<Photo> findByPerson(UserEntity user, PersonEntity personEntity, Pageable pageable) {
        return photoRepository.findByUserAndFacesPerson(user, personEntity, pageable).map(photoMapper::photoEntityToPhoto);
    }

    public Optional<PhotoEntity> findById(UserEntity user, UUID id) {
        return photoRepository.findByIdAndUser(id, user);
    }


}