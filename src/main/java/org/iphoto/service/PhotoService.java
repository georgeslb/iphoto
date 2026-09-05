package org.iphoto.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.iphoto.entities.*;
import org.iphoto.exception.*;
import org.iphoto.mappers.PhotoMapper;
import org.iphoto.model.*;
import org.iphoto.model.request.PhotoEditRequest;
import org.iphoto.model.request.PhotoSelectionRequest;
import org.iphoto.repository.AlbumRepository;
import org.iphoto.repository.PhotoRepository;
import org.springframework.data.domain.*;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

import static org.iphoto.model.UploadStatus.DUPLICATED;
import static org.iphoto.model.UploadStatus.TRASHED;

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
    private final MenuService menuService;
    private final PhotoUploadService photoUploadService;
    private final UserService userService;

    public Page<Photo> getPhotos(UUID spaceId, Pageable pageable) {
        return buildDedupedPage(photoRepository.findBySpaceIdAndIsGarbageFalse(spaceId, pageable));
    }

    // Shared by getPhotos/getPhotosForDay above: attaches each photo's known duplicates and drops
    // any photo from the top-level page that is itself someone else's duplicate (it's already
    // shown attached to its primary), so the same photo never appears twice in one page.
    private Page<Photo> buildDedupedPage(Page<PhotoEntity> rawPage) {
        Set<Photo> duplicatedPhotos = new CopyOnWriteArraySet<>();
        Page<Photo> firstSelection = rawPage.map(entity -> {
            Set<PhotoEntity> duplicatesEntities = duplicationService.getDuplicates(entity);
            Set<Photo> duplicates = duplicatesEntities != null ? duplicatesEntities.stream().map(photoMapper::photoEntityToPhoto).collect(Collectors.toSet()) : Collections.emptySet();
            duplicatedPhotos.addAll(duplicates);
            return photoMapper.photoEntityToPhoto(entity, duplicates);
        });
        List<Photo> contentPhotos = new ArrayList<>(firstSelection.getContent());
        contentPhotos.removeAll(duplicatedPhotos);
        return new PageImpl<>(contentPhotos, firstSelection.getPageable(), firstSelection.getTotalElements());
    }

    // Day-index browsing (replaces the old month-timeline this app used to have): one row per
    // day that has a non-garbage photo in this space, newest first. Fetched once up front so the
    // client can render every day section immediately and only lazily fetch each day's actual
    // photos (getPhotosForDay) as its section scrolls into view.
    public List<DayBucketDTO> getDailyPhotoCounts(UUID spaceId) {
        return photoRepository.findDailyPhotoCountsWithCoverBySpaceId(spaceId).stream()
                .map(row -> new DayBucketDTO(
                        row.getDay(),
                        row.getCount(),
                        "/api/photos/" + row.getPhotoId() + "/thumbnail"
                                + (row.getFileHash() != null ? "?v=" + row.getFileHash() : "")))
                .toList();
    }

    // One page of a single day's photos - same dedup treatment as the plain feed (getPhotos)
    // so duplicate-clustering stays consistent between the two.
    public PageResponse<Photo> getPhotosForDay(UUID spaceId, LocalDate day, Pageable pageable) {
        return PageResponse.from(buildDedupedPage(photoRepository.findBySpaceIdAndIsGarbageFalseAndGroupedDay(spaceId, day, pageable)));
    }

    // Garbage's day-index equivalent of getDailyPhotoCounts/getPhotosForDay above - cross-space
    // (a user's garbage isn't scoped to one space), no dedup treatment (matching the old flat
    // getGarbagePhotos this replaces, which never called buildDedupedPage either).
    public List<DayBucketDTO> getGarbageDailyPhotoCounts(UserEntity user) {
        Set<UUID> allUserSpacesIds = spaceService.getAllSpaces(user).stream().map(SpaceDTO::id).collect(Collectors.toSet());
        return photoRepository.findDailyPhotoCountsWithCoverBySpaceIdInAndIsGarbageTrue(allUserSpacesIds).stream()
                .map(row -> new DayBucketDTO(
                        row.getDay(),
                        row.getCount(),
                        "/api/photos/" + row.getPhotoId() + "/thumbnail"
                                + (row.getFileHash() != null ? "?v=" + row.getFileHash() : "")))
                .toList();
    }

    public Page<Photo> getGarbagePhotosForDay(UserEntity user, LocalDate day, Pageable pageable) {
        Set<UUID> allUserSpacesIds = spaceService.getAllSpaces(user).stream().map(SpaceDTO::id).collect(Collectors.toSet());
        return photoRepository.findBySpaceIdInAndIsGarbageTrueAndGroupedDay(allUserSpacesIds, day, pageable).map(photoMapper::photoEntityToPhoto);
    }

    public Page<Photo> getSpacePhotos(UserEntity currentUser, UUID spaceId, Pageable pageable) {
        SpaceDTO space = spaceService.getSpaceByIdForUser(spaceId, currentUser).orElseThrow(NoMatchUserSpaceException::new);
        return getPhotos(space.id(), pageable);
    }

    @Transactional
    @Retryable(maxAttempts = 5, retryFor = {SpaceLockingException.class}, backoff = @Backoff(delay = 1000))
    public void restoreAssetsFromGarbage(UserEntity currentUser, List<UUID> ids) {
        List<PhotoEntity> photos = photoRepository.findByIdIn(ids).stream().toList();

        for (PhotoEntity photo : photos) {
            if (photo == null || !photo.getIsGarbage()) continue;
            SpaceEntity space = photo.getSpace();
            if (!space.getUser().getId().equals(currentUser.getId())) continue;

            try (SpaceService.AutoClosableLock ignore = spaceService.lockSpace(space)) {
                spaceService.increaseSpacePhotoCount(space);
                photo.setIsGarbage(false);
                photo.setGarbageAt(null);
                photoRepository.save(photo);
                taskMapper.updateTasksToAction(TaskMapper.Action.PHOTO_REMOVED_FROM_GARBAGE, photo.getId());
            }
        }
        duplicationService.restoreFromGarbage(photos);
    }

    @Transactional
    @Retryable(maxAttempts = 5, retryFor = {SpaceLockingException.class}, backoff = @Backoff(delay = 1000))
    public void moveAssetsToGarbage(UserEntity currentUser, List<UUID> ids) throws Exception {

        Set<PhotoEntity> photos = photoRepository.findByIdIn(ids);

        for (PhotoEntity photo : photos) {
            if (photo == null || photo.getIsGarbage()) continue;
            SpaceEntity space = photo.getSpace();
            if (!space.getUser().getId().equals(currentUser.getId())) continue;
            try (SpaceService.AutoClosableLock ignore = spaceService.lockSpace(space)) {
                spaceService.decreaseSpacePhotoCount(space);
                photo.setIsGarbage(true);
                photo.setGarbageAt(LocalDateTime.now());
                photoRepository.save(photo);
                taskMapper.updateTasksToAction(TaskMapper.Action.PHOTO_MOVED_TO_GARBAGE, photo.getId());
            }
        }

        duplicationService.processMovingToGarbage(photos);
        photos.stream().map(PhotoEntity::getAlbums).flatMap(Collection::stream)
                .forEach(album -> albumService.removePhotosFromAlbum(album.getId(), ids));
    }

    public UploadStatus uploadAsset(TaskEntity task, Map<String, String> payload, File tempFile) throws FileNotFoundException {
        String hash;
        UserEntity user = userService.findById(UUID.fromString(payload.get("userId"))).orElseThrow(NoUserFoundException::new);
        try (FileInputStream fis = new FileInputStream(tempFile)) {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(fis.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        return photoRepository.findByFileHash(user.getId() + hash).map(photoEntity -> {
            try {
                Files.deleteIfExists(tempFile.toPath());
                task.setPhotoId(photoEntity.getId());
                if (photoEntity.getIsGarbage()) {
                    return TRASHED;
                }
                return DUPLICATED;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        }).orElseGet(() -> {
            try {
                return photoUploadService.processNewAsset(task, user, payload, tempFile, hash);
            } catch (Exception e) {
                throw new PhotoUploadException(e);
            }
        });
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
                log.info("permanently deleting photo with id {}", photo.getId());
                if (userSpaces == null || userSpaces.stream().anyMatch(userSpace -> userSpace.id().equals(photo.getSpace().getId()))) {
                    photoDeletionService.permanentDeleteAssets(photo.getId());
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
                throw new PhotoDeletionException("can't delete photo with id "+  photo.getId(), e);
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
            return photoMapper.photoEntityToPhoto(editPhotoService.editImage(user, id, file, request));
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

    // Person's day-index equivalent of getDailyPhotoCounts/getPhotosForDay above - joined through
    // faces rather than scoped by space_id, since a person's photos can span multiple spaces. No
    // dedup treatment, matching findByPerson above (Person view has never had that either).
    public List<DayBucketDTO> getPersonDailyPhotoCounts(PersonEntity person) {
        return photoRepository.findDailyPhotoCountsWithCoverByPersonId(person.getId()).stream()
                .map(row -> new DayBucketDTO(
                        row.getDay(),
                        row.getCount(),
                        "/api/photos/" + row.getPhotoId() + "/thumbnail"
                                + (row.getFileHash() != null ? "?v=" + row.getFileHash() : "")))
                .toList();
    }

    public Page<Photo> getPersonPhotosForDay(PersonEntity person, LocalDate day, Pageable pageable) {
        return photoRepository.findByFacesPersonAndGroupedDay(person, day, pageable).map(photoMapper::photoEntityToPhoto);
    }

    public Optional<PhotoEntity> findById(UUID id) {
        return photoRepository.findById(id);
    }

    @Transactional
    @Retryable(maxAttempts = 5, retryFor = {SpaceLockingException.class}, backoff = @Backoff(delay = 1000))
    public void moveAssetsToMain(UserEntity user, List<UUID> ids) throws Exception {
        SpaceEntity mainUserSpace = spaceService.getDefaultSpaceForUser(user);
        try (SpaceService.AutoClosableLock ignore = spaceService.lockSpace(mainUserSpace)){
            photoRepository.findByIdIn(ids).forEach(photo -> {
                try (SpaceService.AutoClosableLock ignore2 = spaceService.lockSpace(photo.getSpace())){
                    spaceService.decreaseSpacePhotoCount(photo.getSpace());
                    photo.setSpace(mainUserSpace);
                    spaceService.increaseSpacePhotoCount(mainUserSpace);
                    photoRepository.save(photo);
                    taskMapper.updateTasksToAction(TaskMapper.Action.PHOTO_MOVED_TO_MAIN, photo.getId());
                } catch (Exception e) {
                    throw new PhotoMoveToMainException("can't move photo " + photo.getId(), e);
                }
            });
        }
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
                    (spaces.size() == 1
                    && spaces.stream().findAny().get().getType() == SpaceType.MAIN) || photoSelectionRequest.menuSelectionRequest().menu().equals("library")) {
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

    // Backs POST /api/photos/check-hashes - lets a client (mobile camera-sync) skip re-uploading
    // files the server already has, cheaply, before spending bandwidth on them.
    public List<String> checkExistingHashes(List<String> hashes) {
        if (hashes == null || hashes.isEmpty()) {
            return List.of();
        }
        return photoRepository.findExistingFileHashes(hashes);
    }
}