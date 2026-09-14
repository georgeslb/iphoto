package org.iphoto.service;

import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.TaskEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.AlbumDTO;
import org.iphoto.model.DayBucketDTO;
import org.iphoto.model.MemoryDTO;
import org.iphoto.model.PageResponse;
import org.iphoto.model.Photo;
import org.iphoto.model.UploadStatus;
import org.iphoto.model.request.PhotoEditRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface PhotoService {

    Page<Photo> getPhotos(UUID spaceId, Pageable pageable);

    // Day-index browsing (replaces the old month-timeline this app used to have): one row per
    // day that has a non-garbage photo in this space, newest first. Cached (see DayIndexCache) -
    // evicted on every mutation that can change a space's day counts/covers (upload, move to/from
    // garbage, move to main, date edit). isVideo is the photo/video type filter (null = every
    // type).
    List<DayBucketDTO> getDailyPhotoCounts(UUID spaceId, Boolean isVideo);

    // Library's "N months/years ago" bar - one candidate window per configured offset (see
    // IPhotoProperties.Memories.offsets/MemoryOffset), each resolved to random non-garbage photos
    // taken anywhere within that window.
    List<MemoryDTO> getMemories(UUID spaceId);

    // One page of a single day's photos - same dedup treatment as the plain feed (getPhotos).
    // isVideo: see getDailyPhotoCounts above.
    PageResponse<Photo> getPhotosForDay(UUID spaceId, LocalDate day, Pageable pageable, Boolean isVideo);

    // Garbage's day-index equivalent of getDailyPhotoCounts/getPhotosForDay above - cross-space
    // (a user's garbage isn't scoped to one space), no dedup treatment. isVideo: see
    // getDailyPhotoCounts above.
    List<DayBucketDTO> getGarbageDailyPhotoCounts(UserEntity user, Boolean isVideo);

    Page<Photo> getGarbagePhotosForDay(UserEntity user, LocalDate day, Pageable pageable, Boolean isVideo);

    Page<Photo> getSpacePhotos(UserEntity currentUser, UUID spaceId, Pageable pageable);

    // Space-scoped garbage listing (mobile folder-sync only - the web app uses the cross-space
    // day-indexed getGarbagePhotosForDay above). No dedup treatment, unlike getSpacePhotos.
    Page<Photo> getSpaceGarbagePhotos(UserEntity currentUser, UUID spaceId, Pageable pageable);

    void restoreAssetsFromGarbage(UserEntity currentUser, List<UUID> ids);

    void moveAssetsToGarbage(UserEntity currentUser, List<UUID> ids) throws Exception;

    UploadStatus uploadAsset(TaskEntity task, Map<String, String> payload, File tempFile) throws FileNotFoundException;

    /**
     * called only from system
     * @param photoId
     */
    void permanentDeleteAssets(List<UUID> photoId);

    /**
     *  called only by the user from frontend
     * @param user
     * @param ids
     */
    void permanentDeleteAssets(UserEntity user, List<UUID> ids);

    Photo updatePhotoDate(UserEntity user, UUID id, LocalDateTime newDate);

    /**
     * Returns final saved image path.
     */
    Photo editPhoto(
            UserEntity user,
            UUID id,
            MultipartFile file, PhotoEditRequest request);

    List<AlbumDTO> getAlbumsByPhoto(UUID photoId);

    Page<Photo> findByPerson(PersonEntity personEntity, Pageable pageable);

    // Person's day-index equivalent of getDailyPhotoCounts/getPhotosForDay above - joined through
    // faces rather than scoped by space_id, since a person's photos can span multiple spaces.
    List<DayBucketDTO> getPersonDailyPhotoCounts(PersonEntity person);

    Page<Photo> getPersonPhotosForDay(PersonEntity person, LocalDate day, Pageable pageable);

    Optional<PhotoEntity> findById(UUID id);

    // Authorization boundary for bulk actions that read files directly off disk (bulk download) -
    // unlike a plain findByIdIn, this silently drops any id the caller doesn't actually own rather
    // than serving it, so a client can't read another user's files by passing arbitrary ids.
    List<PhotoEntity> findOwnedByIds(UserEntity user, List<UUID> ids);

    void moveAssetsToMain(UserEntity user, List<UUID> ids) throws Exception;

    void deletePhotoInSpace(UserEntity userEntity, UUID spaceId);

    // Backs POST /api/photos/check-hashes - lets a client (mobile camera-sync) skip re-uploading
    // files the server already has, cheaply, before spending bandwidth on them.
    List<String> checkExistingHashes(List<String> hashes);
}
