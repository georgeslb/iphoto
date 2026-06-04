package org.iphoto.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.*;
import org.iphoto.mappers.PhotoMapper;
import org.iphoto.model.AlbumDTO;
import org.iphoto.model.Photo;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;
import org.iphoto.model.request.PhotoEditRequest;
import org.iphoto.repository.AlbumRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.SpaceRepository;
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
    private final SpaceRepository spaceRepository;

    public Page<Photo> getPhotos(SpaceEntity space, Pageable pageable) {
        return photoRepository.findBySpaceIdAndIsGarbageFalse(space.getId(), pageable).map(photoMapper::photoEntityToPhoto);
    }

    public Page<Photo> getGarbagePhotos(UserEntity user, Pageable pageable) {
        return photoRepository.findBySpaceInAndIsGarbageTrue(spaceRepository.findByUser(user), pageable).map(photoMapper::photoEntityToPhoto);
    }

    @Transactional
    public void restoreAssetsFromGarbage(List<UUID> ids) {
        List<PhotoEntity> photos = ids.stream()
                .map(photoRepository::findById)
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
                if ("PERMANENT_DELETE".equals(task.getTaskType()) && photo.getId() != null) {
                    task.setStatus(TaskStatus.IGNORED);
                    task.setErrorMessage("Asset was restored by user, permanent delete task cancelled.");
                    taskManagerService.updateTask(task);
                }
            }
        }
        duplicationService.restoreFromGarbage(photos);
    }

    @Transactional
    public void moveAssetsToGarbage(UserEntity currentUser, List<UUID> ids) {

        Set<PhotoEntity> photos = photoRepository.findByIdIn(ids);

        for (PhotoEntity photo : photos) {
            if (photo == null || photo.getIsGarbage()) continue;
            if (photo.getSpace().getUser().getId().equals(currentUser.getId())) continue;
            photo.setIsGarbage(true);
            photo.setGarbageAt(LocalDateTime.now());
            photoRepository.save(photo);

            taskManagerService
                    .createTaskEntity(TaskType.PERMANENT_DELETE, photo.getId(), null, LocalDateTime.now().plusDays(30));
        }

        duplicationService.processMovingToGarbage(photos);
        photos.stream().map(PhotoEntity::getAlbums).flatMap(Collection::stream)
                .forEach(album -> albumService.removePhotosFromAlbum(album.getId(), ids)); // Pass user to albumService
    }

    @Transactional
    public void permanentDeleteAssets(List<UUID> photoId) {
        Set<PhotoEntity> photos = photoRepository.findByIdIn(photoId);
        deleteAssets(photos);
    }

    @Transactional
    public void permanentDeleteAssets(UserEntity user, List<UUID> ids) {
        SpaceEntity space = spaceRepository.findByTypeEqualsMainAndUser(user);
        Set<PhotoEntity> photos = photoRepository.findBySpaceIdAndIdIn(space.getId(), ids);
        deleteAssets(photos);

    }

    private void deleteAssets(Set<PhotoEntity> photos) {
        photos.parallelStream().forEach(photo -> {
            try {
                String originalPath = photo.getOriginalPath();
                String thumbnailPath = photo.getThumbnailPath();
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
    public Photo updatePhotoDate(UUID id, LocalDateTime newDate) {
        PhotoEntity photoEntity = photoRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Photo not found with ID: " + id));
        photoEntity.setDateTaken(newDate);
        photoRepository.save(photoEntity);
        return photoMapper.photoEntityToPhoto(photoEntity);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PhotoEntity fetchNewPhotoFromDatabaseAsync(UUID id) {
        return photoRepository.findById(id)
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

    public List<AlbumDTO> getAlbumsByPhoto(UUID photoId) {
        return albumRepository.findByPhotos_Id(photoId).stream().map(albumService::convertToDto).toList();
    }

    public Page<Photo> findByPerson(PersonEntity personEntity, Pageable pageable) {
        return photoRepository.findByFacesPerson(personEntity, pageable).map(photoMapper::photoEntityToPhoto);
    }

    public Optional<PhotoEntity> findById(UUID id) {
        return photoRepository.findById(id);
    }

    @Transactional
    public void moveAssetsToMain(UserEntity user, List<UUID> ids) {
        SpaceEntity mainUserSpace = spaceRepository.findByTypeEqualsMainAndUser(user);
        photoRepository.findByIdIn(ids).forEach(photo -> {
            photo.setSpace(mainUserSpace);
            photoRepository.save(photo);
        });
    }
}