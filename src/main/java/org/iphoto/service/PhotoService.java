package org.iphoto.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.iphoto.entities.*;
import org.iphoto.exception.NoMatchUserSpaceException;
import org.iphoto.exception.PhotoDeletionException;
import org.iphoto.mappers.PhotoMapper;
import org.iphoto.model.AlbumDTO;
import org.iphoto.model.Photo;
import org.iphoto.model.PhotoSelectionDTO;
import org.iphoto.model.SpaceDTO;
import org.iphoto.model.request.PhotoEditRequest;
import org.iphoto.model.request.PhotoSelectionRequest;
import org.iphoto.repository.AlbumRepository;
import org.iphoto.repository.PhotoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
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
    private final SpaceService spaceService;
    final MenuService menuService;

    public Page<Photo> getPhotos(UUID spaceId, Pageable pageable) {
        return photoRepository.findBySpaceIdAndIsGarbageFalse(spaceId, pageable).map(photoMapper::photoEntityToPhoto);
    }

    public Page<Photo> getGarbagePhotos(UserEntity user, Pageable pageable) {
        Set<UUID> allUserSpacesIds = spaceService.getAllSpaces(user).stream().map(SpaceDTO::id).collect(Collectors.toSet());
        return photoRepository.findBySpaceIdInAndIsGarbageTrue(allUserSpacesIds, pageable).map(photoMapper::photoEntityToPhoto);
    }

    public Page<Photo> getSpacePhotos(UserEntity currentUser, UUID spaceId, Pageable pageable) {
        SpaceDTO space = spaceService.getSpaceByIdForUser(spaceId, currentUser).orElseThrow(NoMatchUserSpaceException::new);
        return getPhotos(space.id(), pageable);
    }

    @Transactional
    public void restoreAssetsFromGarbage(UserEntity currentUser, List<UUID> ids) {
        List<PhotoEntity> photos =  photoRepository.findByIdIn(ids).stream().toList();

        for (PhotoEntity photo : photos) {
            if (photo == null || !photo.getIsGarbage()) continue;
            SpaceEntity space = photo.getSpace();
            if (!space.getUser().getId().equals(currentUser.getId())) continue;
            space.setPhotoCount(space.getPhotoCount() + 1);
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
            SpaceEntity space = photo.getSpace();
            if (!space.getUser().getId().equals(currentUser.getId())) continue;
            spaceService.decreaseSpacePhotoCount(space);
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
        deleteAssets(null, photos);
    }

    /**
     *  called only by the user from frontend
     * @param user
     * @param ids
     */
    @Transactional
    public void permanentDeleteAssets(UserEntity user, List<UUID> ids) {
        List<SpaceDTO> userSpaces = spaceService.getAllSpaces(user);
        Set<PhotoEntity> photos = photoRepository.findByIdIn(ids);
        deleteAssets(userSpaces, photos);

    }

    private void deleteAssets(List<SpaceDTO> userSpaces, Set<PhotoEntity> photos) {
        photos.forEach(photo -> {
            try {
                String originalPath = photo.getOriginalPath();
                String thumbnailPath = photo.getThumbnailPath();
                if (userSpaces == null || userSpaces.stream().anyMatch(userSpace -> userSpace.id().equals(photo.getSpace().getId()))) {
                    photoDeletionService.permanentDeleteAssets(photo);
                    try {
                        Files.deleteIfExists(Paths.get(originalPath));
                        if (thumbnailPath != null) {
                            Files.deleteIfExists(Paths.get(thumbnailPath));
                        }
                    } catch (IOException e) {
                        throw new PhotoDeletionException("Error while deleting file fom file system", e);
                    }
                } else {
                    throw new PhotoDeletionException("user trying to delete unauthorized asset");
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
        SpaceEntity mainUserSpace = spaceService.getDefaultSpaceForUser(user);
        photoRepository.findByIdIn(ids).forEach(photo -> {
            spaceService.decreaseSpacePhotoCount(photo.getSpace());
            photo.setSpace(mainUserSpace);
            spaceService.increaseSpacePhotoCount(photo.getSpace());
            photoRepository.save(photo);
            taskMapper.updateTasksToAction(TaskMapper.Action.PHOTO_MOVED_TO_MAIN, photo.getId());
        });
    }

    public void deletePhotoInSpace(UserEntity userEntity, UUID spaceId) {
        Pageable pageable = PageRequest.of(0, 100);
        Slice<PhotoEntity> photosSlice;
        do {
            // Filter photos by user
            photosSlice = photoRepository.findBySpaceId(spaceId, pageable);
            List<UUID> listToMoveToGarbage = photosSlice.getContent().stream().map(PhotoEntity::getId).toList();
            permanentDeleteAssets(userEntity, listToMoveToGarbage);
            pageable = photosSlice.nextPageable();

        } while (photosSlice.hasNext());
    }


    public PhotoSelectionDTO getSelection(PhotoSelectionRequest photoSelectionRequest, UserEntity user) {
        boolean canBeDeleted = false, canBePermanentlyDeleted = false, canBeMovedToMain = false,
                canBeMovedToAlbum = false, canBeRemovedFromAlbum = false, canBeModified = false,
                canBeSetAsCover = false, canBeRestored = false;
        Set<PhotoEntity> selectedPhotos = photoRepository.findByIdIn(photoSelectionRequest.photosId());
        if (selectedPhotos.isEmpty()) {
            return new PhotoSelectionDTO(menuService.getSelection(photoSelectionRequest.menuSelectionRequest()), false, false, false, false, false, false, false, false);
        } else {
            Set<SpaceEntity> spaces = new HashSet<>();
            boolean isGarbageMenu = photoSelectionRequest.menuSelectionRequest().menu().contains("garbage");
            if (CollectionUtils.isNotEmpty(photoSelectionRequest.menuSelectionRequest().spaceIds())) {
                spaces = photoSelectionRequest.menuSelectionRequest().spaceIds().stream()
                        .map(spaceService::getSpaceById)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .filter(spaceEntity -> spaceEntity.getUser().getId().equals(user.getId())).collect(Collectors.toSet());
            }

            if (!isGarbageMenu) {
                canBeDeleted = true;
            }

            boolean isAlbumMenu = photoSelectionRequest.menuSelectionRequest().menu().contains("album");
            if (!isGarbageMenu &&
                    spaces.size() == 1
                    && spaces.stream().findAny().get().getType() == SpaceType.MAIN) {
                canBeMovedToAlbum = true;
                if (isAlbumMenu & isAlbumSelected(photoSelectionRequest)) {
                    canBeRemovedFromAlbum = true;
                }
            }
            if (photoSelectionRequest.menuSelectionRequest().menu().equals("lightbox")) {
                canBeModified = true;
                canBeMovedToAlbum = true;
                canBeRemovedFromAlbum = true;
                if (CollectionUtils.isNotEmpty(selectedPhotos) && selectedPhotos.size() == 1 && selectedPhotos.stream().anyMatch(photoEntity -> photoEntity.getSpace().getType() != SpaceType.MAIN)) {
                    canBeMovedToMain = true;
                }
            }
            if (spaces.size() == 1
                    && spaces.stream().findAny().get().getType() != SpaceType.MAIN
                    && !isGarbageMenu) {
                canBeMovedToMain = true;
            }
            if (isGarbageMenu && CollectionUtils.isNotEmpty(photoSelectionRequest.photosId())) {
                canBePermanentlyDeleted = true;
                canBeRestored = true;
            }
            if (isAlbumMenu
                    && isAlbumSelected(photoSelectionRequest)) {
                canBeSetAsCover = true;
            }
            return new PhotoSelectionDTO(menuService.getSelection(photoSelectionRequest.menuSelectionRequest()), canBeDeleted, canBePermanentlyDeleted, canBeMovedToMain, canBeMovedToAlbum, canBeRemovedFromAlbum, canBeModified, canBeSetAsCover, canBeRestored);
        }
    }

    private boolean isAlbumSelected(PhotoSelectionRequest photoSelectionRequest) {
        return photoSelectionRequest.menuSelectionRequest().albumIds() != null
                && photoSelectionRequest.menuSelectionRequest().albumIds().size() == 1
                && albumRepository.existsById(photoSelectionRequest.menuSelectionRequest().albumIds().get(0));
    }
}