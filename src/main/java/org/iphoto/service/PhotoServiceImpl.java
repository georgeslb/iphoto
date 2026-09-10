package org.iphoto.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.*;
import org.iphoto.exception.*;
import org.iphoto.mappers.PhotoMapper;
import org.iphoto.model.*;
import org.iphoto.model.request.PhotoEditRequest;
import org.iphoto.properties.IPhotoProperties;
import org.iphoto.repository.AlbumRepository;
import org.iphoto.repository.PhotoRepository;
import org.springframework.data.domain.*;
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
public class PhotoServiceImpl implements PhotoService {


    private final PhotoRepository photoRepository;
    private final TaskMapper taskMapper;
    private final PhotoMapper photoMapper;
    private final AlbumService albumService;
    private final PhotoDeletionService photoDeletionService;
    private final DuplicationService duplicationService;
    private final EditPhotoService editPhotoService;
    private final AlbumRepository albumRepository;
    private final SpaceService spaceService;
    private final PhotoUploadService photoUploadService;
    private final UserService userService;
    private final DayIndexCache dayIndexCache;
    private final IPhotoProperties iPhotoProperties;

    @Override
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

    @Override
    public List<DayBucketDTO> getDailyPhotoCounts(UUID spaceId) {
        return dayIndexCache.getSpaceDayCounts(spaceId, () -> DayBucketDTO.from(photoRepository.countDailyPhotosBySpaceId(spaceId)));
    }

    @Override
    public List<MemoryDTO> getMemories(UUID spaceId) {
        LocalDate today = LocalDate.now();
        int photosPerBlock = iPhotoProperties.getMemories().getPhotosPerBlock();
        return iPhotoProperties.getMemories().getOffsets().stream()
                .map(raw -> {
                    MemoryOffset offset;
                    try {
                        offset = MemoryOffset.parse(raw);
                    } catch (IllegalArgumentException e) {
                        log.warn("Skipping invalid iphoto.memories.offsets entry '{}': {}", raw, e.getMessage());
                        return null;
                    }
                    LocalDate anchor = today.minusMonths(offset.monthsAgo());
                    LocalDate startDay = anchor.minusDays(offset.beforeDays());
                    LocalDate endDay = anchor.plusDays(offset.afterDays());
                    List<Photo> photos = photoRepository.findRandomPhotosBySpaceIdAndGroupedDayBetween(spaceId, startDay, endDay, photosPerBlock).stream()
                            .map(photoMapper::photoEntityToPhoto)
                            .toList();
                    return photos.isEmpty() ? null : new MemoryDTO(offset.monthsAgo(), anchor, photos);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public PageResponse<Photo> getPhotosForDay(UUID spaceId, LocalDate day, Pageable pageable) {
        return PageResponse.from(buildDedupedPage(photoRepository.findBySpaceIdAndIsGarbageFalseAndGroupedDay(spaceId, day, pageable)));
    }

    @Override
    public List<DayBucketDTO> getGarbageDailyPhotoCounts(UserEntity user) {
        Set<UUID> allUserSpacesIds = spaceService.getAllSpaces(user).stream().map(SpaceDTO::id).collect(Collectors.toSet());
        return DayBucketDTO.from(photoRepository.countDailyGarbagePhotosBySpaceIds(allUserSpacesIds));
    }

    @Override
    public Page<Photo> getGarbagePhotosForDay(UserEntity user, LocalDate day, Pageable pageable) {
        Set<UUID> allUserSpacesIds = spaceService.getAllSpaces(user).stream().map(SpaceDTO::id).collect(Collectors.toSet());
        return photoRepository.findBySpaceIdInAndIsGarbageTrueAndGroupedDay(allUserSpacesIds, day, pageable).map(photoMapper::photoEntityToPhoto);
    }

    @Override
    public Page<Photo> getSpacePhotos(UserEntity currentUser, UUID spaceId, Pageable pageable) {
        SpaceDTO space = spaceService.getSpaceByIdForUser(spaceId, currentUser).orElseThrow(NoMatchUserSpaceException::new);
        return getPhotos(space.id(), pageable);
    }

    @Override
    @Transactional
    public void restoreAssetsFromGarbage(UserEntity currentUser, List<UUID> ids) {
        List<PhotoEntity> photos = photoRepository.findByIdIn(ids).stream().toList();

        for (PhotoEntity photo : photos) {
            if (photo == null || !photo.getIsGarbage()) continue;
            SpaceEntity space = photo.getSpace();
            if (!space.getUser().getId().equals(currentUser.getId())) continue;

            spaceService.increaseSpacePhotoCount(space);
            photo.setIsGarbage(false);
            photo.setGarbageAt(null);
            photoRepository.save(photo);
            taskMapper.updateTasksToAction(TaskMapper.Action.PHOTO_REMOVED_FROM_GARBAGE, photo.getId());
            dayIndexCache.evictSpace(space.getId());
        }
        duplicationService.restoreFromGarbage(photos);
    }

    @Override
    @Transactional
    public void moveAssetsToGarbage(UserEntity currentUser, List<UUID> ids) throws Exception {

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
            dayIndexCache.evictSpace(space.getId());
        }

        duplicationService.processMovingToGarbage(photos);
        photos.stream().map(PhotoEntity::getAlbums).flatMap(Collection::stream)
                .forEach(album -> albumService.removePhotosFromAlbum(album.getId(), ids));
    }

    @Override
    public UploadStatus uploadAsset(TaskEntity task, Map<String, String> payload, File tempFile) throws FileNotFoundException {
        String hash;
        try (FileInputStream fis = new FileInputStream(tempFile)) {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(fis.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        return photoRepository.findByFileHash(hash).map(photoEntity -> {
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
                return photoUploadService.processNewAsset(task, UUID.fromString(payload.get("userId")), payload, tempFile, hash);
            } catch (Exception e) {
                throw new PhotoUploadException(e);
            }
        });
    }

    @Override
    @Transactional
    public void permanentDeleteAssets(List<UUID> photoId) {
        Set<PhotoEntity> photos = photoRepository.findByIdIn(photoId);
        deleteAssets(null, photos);
    }

    @Override
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

    @Override
    @Transactional
    public Photo updatePhotoDate(UserEntity user, UUID id, LocalDateTime newDate) {
        PhotoEntity photoEntity = photoRepository.findById(id)
                .filter(p -> p.getSpace().getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new NoSuchElementException("Photo not found with ID: " + id));
        photoEntity.setDateTaken(newDate);
        photoRepository.save(photoEntity);
        // dateTaken drives groupedDay (see PhotoEntity.syncGroupedDay), so this can move the photo
        // to a different day bucket entirely - evict rather than try to patch the cached counts.
        dayIndexCache.evictSpace(photoEntity.getSpace().getId());
        return photoMapper.photoEntityToPhoto(photoEntity);
    }

    @Override
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

    @Override
    public List<AlbumDTO> getAlbumsByPhoto(UUID photoId) {
        return albumRepository.findByPhotos_Id(photoId).stream().map(albumService::convertToDto).toList();
    }

    @Override
    public Page<Photo> findByPerson(PersonEntity personEntity, Pageable pageable) {
        return photoRepository.findByFacesPerson(personEntity, pageable).map(photoMapper::photoEntityToPhoto);
    }

    @Override
    public List<DayBucketDTO> getPersonDailyPhotoCounts(PersonEntity person) {
        return DayBucketDTO.from(photoRepository.countDailyPhotosByPersonId(person.getId()));
    }

    @Override
    public Page<Photo> getPersonPhotosForDay(PersonEntity person, LocalDate day, Pageable pageable) {
        return photoRepository.findByFacesPersonAndGroupedDay(person, day, pageable).map(photoMapper::photoEntityToPhoto);
    }

    @Override
    public Optional<PhotoEntity> findById(UUID id) {
        return photoRepository.findById(id);
    }

    @Override
    public List<PhotoEntity> findOwnedByIds(UserEntity user, List<UUID> ids) {
        return photoRepository.findByIdInAndSpaceUserId(ids, user.getId());
    }

    @Override
    @Transactional
    public void moveAssetsToMain(UserEntity user, List<UUID> ids) throws Exception {
        SpaceEntity mainUserSpace = spaceService.getDefaultSpaceForUser(user);
        photoRepository.findByIdIn(ids).forEach(photo -> {
            // findByIdIn isn't user-scoped - skip anything the caller doesn't own rather than
            // relocating another user's photo into the caller's main space (same guard
            // moveAssetsToGarbage/restoreAssetsFromGarbage already apply).
            if (!photo.getSpace().getUser().getId().equals(user.getId())) return;
            UUID originalSpaceId = photo.getSpace().getId();
            try {
                photo.setSpace(mainUserSpace);
                photoRepository.save(photo);
                taskMapper.updateTasksToAction(TaskMapper.Action.PHOTO_MOVED_TO_MAIN, photo.getId());
                dayIndexCache.evictSpace(originalSpaceId);
                dayIndexCache.evictSpace(mainUserSpace.getId());
                spaceService.decreaseSpacePhotoCount(photo.getSpace());
                spaceService.increaseSpacePhotoCount(mainUserSpace);
            } catch (Exception e) {
                throw new PhotoMoveToMainException("can't move photo " + photo.getId(), e);
            }
        });
    }

    @Override
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


    @Override
    public List<String> checkExistingHashes(List<String> hashes) {
        if (hashes == null || hashes.isEmpty()) {
            return List.of();
        }
        return photoRepository.findExistingFileHashes(hashes);
    }
}
