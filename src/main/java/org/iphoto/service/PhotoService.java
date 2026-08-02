package org.iphoto.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.exception.PhotoDeletionException;
import org.iphoto.mappers.PhotoMapper;
import org.iphoto.model.AlbumDTO;
import org.iphoto.model.Photo;
import org.iphoto.model.request.PhotoEditRequest;
import org.iphoto.repository.AlbumRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.SpaceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
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
    private final TaskMapper taskMapper;
    private final PhotoMapper photoMapper;
    private final AlbumService albumService;
    private final PhotoDeletionService photoDeletionService;
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
            taskMapper.updateTasksToAction(TaskMapper.Action.PHOTO_REMOVED_FROM_GARBAGE, photo.getId());

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
            taskMapper.updateTasksToAction(TaskMapper.Action.PHOTO_MOVED_TO_GARBAGE, photo.getId());
        }

        duplicationService.processMovingToGarbage(photos);
        photos.stream().map(PhotoEntity::getAlbums).flatMap(Collection::stream)
                .forEach(album -> albumService.removePhotosFromAlbum(album.getId(), ids));
    }

    /**
     * called only from system
     * @param photoId
     */
    @Transactional
    public void permanentDeleteAssets(List<UUID> photoId) {
        Set<PhotoEntity> photos = photoRepository.findByIdIn(photoId);
        deleteAssets(photos);
    }

    /**
     *  called only by the user from frontend
     * @param user
     * @param ids
     */
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
                photoDeletionService.permanentDeleteAssets(photo);
                try {
                    Files.deleteIfExists(Paths.get(originalPath));
                    if (thumbnailPath != null) {
                        Files.deleteIfExists(Paths.get(thumbnailPath));
                    }
                } catch (IOException e) {
                    throw new PhotoDeletionException("Error while deleting file fom file system", e);
                }
            } catch (Exception e) {
                log.error("can't delete photo with id {}", photo.getId(), e);
                throw new PhotoDeletionException("can't delete photo with id {}", e);
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
            taskMapper.updateTasksToAction(TaskMapper.Action.PHOTO_MOVED_TO_MAIN, photo.getId());
        });
    }
}