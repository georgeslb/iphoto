package org.iphoto.repository;

import org.iphoto.entities.AlbumEntity;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PhotoEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface PhotoRepository extends JpaRepository<PhotoEntity, UUID> {

    Page<PhotoEntity> findBySpaceIdAndIsGarbageFalse(UUID spaceId, Pageable pageable);

    Page<PhotoEntity> findByIsGarbageFalseAndAlbums(Set<AlbumEntity> albums, Pageable pageable);

    // Day-index browsing (replaces the old month-timeline/jumpToMonth-cursor machinery, and now
    // the only technology every photo-listing view - Library/Space/Album/Garbage/Person - uses):
    // one page of a single day's photos, for Library/Space (groupedDay is kept in sync with
    // dateTaken via PhotoEntity's onCreate/onUpdate) and Album (joined through the albums
    // collection) respectively.
    Page<PhotoEntity> findBySpaceIdAndIsGarbageFalseAndGroupedDay(UUID spaceId, LocalDate groupedDay, Pageable pageable);

    Page<PhotoEntity> findByIsGarbageFalseAndAlbumsAndGroupedDay(Set<AlbumEntity> albums, LocalDate groupedDay, Pageable pageable);

    // Garbage's day-index equivalent - cross-space (spaceIdIn, matching the flat endpoint this
    // replaced) since a user's garbage isn't scoped to one space.
    Page<PhotoEntity> findBySpaceIdInAndIsGarbageTrueAndGroupedDay(Set<UUID> spaceIds, LocalDate groupedDay, Pageable pageable);

    Page<PhotoEntity> findByFacesPerson(PersonEntity person, Pageable pageable);

    // Person's day-index equivalent - deliberately no isGarbage filter, matching findByFacesPerson
    // above (Person view has never excluded garbage photos).
    Page<PhotoEntity> findByFacesPersonAndGroupedDay(PersonEntity person, LocalDate groupedDay, Pageable pageable);

    long countByIsGarbageFalseAndAlbums(Set<AlbumEntity> album);

    Slice<PhotoEntity> findBySpaceIdAndDateTakenBetweenAndPhashNotNullAndMhashNotNullAndIsGarbageFalse(UUID spaceId, LocalDateTime startDateTaken, LocalDateTime endDateTaken, Pageable pageable);

    // Add a method to find a photo by ID and user
    Optional<PhotoEntity> findById(UUID id);

    Set<PhotoEntity> findBySpaceIdAndIdIn(UUID space, List<UUID> ids);

    Page<PhotoEntity> findBySpaceId(UUID space, Pageable pageable);

    Optional<PhotoEntity> findByFileHash(String hash);

    // Backs POST /api/photos/check-hashes (mobile camera-sync: "which of these do you already
    // have"). Deliberately a global lookup, not @MainSpaceLimited/per-user - findByFileHash above
    // already isn't user-scoped either (TaskWorkerManager's upload-hashing and
    // ImmichImportService both rely on file-hash uniqueness being a global, not per-user,
    // invariant), so this mirrors that existing semantic rather than giving a client an answer
    // inconsistent with what will actually happen when it uploads. A projection to just the hash
    // string, never the photo id/owner, keeps this from becoming a cross-user existence oracle
    // beyond "this exact byte content is stored somewhere."
    @Query("SELECT p.fileHash FROM PhotoEntity p WHERE p.fileHash IN :hashes")
    List<String> findExistingFileHashes(@Param("hashes") Collection<String> hashes);

    Set<PhotoEntity> findByIdIn(List<UUID> photoId);

    // Used by the share/guest flow to confirm a photo actually belongs to the album a share
    // link grants access to, before serving it (guest sessions never have space-filtering
    // applied, so this containment check is the real authorization boundary for them).
    Optional<PhotoEntity> findByIdAndAlbums_IdAndIsGarbageFalse(UUID id, UUID albumId);

    // Day-index: the Library/Space equivalent of the old month-timeline query this replaced,
    // just grouped by the indexed groupedDay column instead of extracting year/month from
    // date_taken live on every call - one row per day that has a non-garbage photo, newest first,
    // each with a randomly-picked cover (same "fresh pick every call" rationale as the month
    // version).
    @Query(value = "select day, count, photo_id, file_hash from (" +
            "  select" +
            "    p.grouped_day as day," +
            "    count(*) over (partition by p.grouped_day) as count," +
            "    p.id as photo_id," +
            "    p.file_hash as file_hash," +
            "    row_number() over (partition by p.grouped_day order by random()) as rn" +
            "  from photos p" +
            "  where p.space_id = :spaceId and p.is_garbage = false and p.grouped_day is not null" +
            ") days_with_cover" +
            " where rn = 1" +
            " order by day desc", nativeQuery = true)
    List<DayBucketProjection> findDailyPhotoCountsWithCoverBySpaceId(@Param("spaceId") UUID spaceId);

    // Same shape, scoped through the album_photos join instead of space_id - the day-index
    // equivalent for Album detail (an album references photos rather than owning them, so this
    // can't be scoped by space_id the way the method above is).
    @Query(value = "select day, count, photo_id, file_hash from (" +
            "  select" +
            "    p.grouped_day as day," +
            "    count(*) over (partition by p.grouped_day) as count," +
            "    p.id as photo_id," +
            "    p.file_hash as file_hash," +
            "    row_number() over (partition by p.grouped_day order by random()) as rn" +
            "  from photos p" +
            "  join album_photos ap on ap.photo_id = p.id" +
            "  where ap.album_id = :albumId and p.is_garbage = false and p.grouped_day is not null" +
            ") days_with_cover" +
            " where rn = 1" +
            " order by day desc", nativeQuery = true)
    List<DayBucketProjection> findDailyPhotoCountsWithCoverByAlbumId(@Param("albumId") UUID albumId);

    // Garbage's day-index equivalent - same shape, opposite is_garbage filter and cross-space
    // (space_id in (:spaceIds)) instead of a single space_id, matching getGarbagePhotosForDay's
    // cross-space scope.
    @Query(value = "select day, count, photo_id, file_hash from (" +
            "  select" +
            "    p.grouped_day as day," +
            "    count(*) over (partition by p.grouped_day) as count," +
            "    p.id as photo_id," +
            "    p.file_hash as file_hash," +
            "    row_number() over (partition by p.grouped_day order by random()) as rn" +
            "  from photos p" +
            "  where p.space_id in (:spaceIds) and p.is_garbage = true and p.grouped_day is not null" +
            ") days_with_cover" +
            " where rn = 1" +
            " order by day desc", nativeQuery = true)
    List<DayBucketProjection> findDailyPhotoCountsWithCoverBySpaceIdInAndIsGarbageTrue(@Param("spaceIds") Set<UUID> spaceIds);

    // Person's day-index equivalent - joined through the faces table instead of space_id/
    // album_photos, since a person's photos aren't scoped to one space. No is_garbage filter,
    // matching findByFacesPerson's existing (no-filter) semantics.
    @Query(value = "select day, count, photo_id, file_hash from (" +
            "  select" +
            "    p.grouped_day as day," +
            "    count(*) over (partition by p.grouped_day) as count," +
            "    p.id as photo_id," +
            "    p.file_hash as file_hash," +
            "    row_number() over (partition by p.grouped_day order by random()) as rn" +
            "  from photos p" +
            "  join faces f on f.photo_id = p.id" +
            "  where f.person_id = :personId and p.grouped_day is not null" +
            ") days_with_cover" +
            " where rn = 1" +
            " order by day desc", nativeQuery = true)
    List<DayBucketProjection> findDailyPhotoCountsWithCoverByPersonId(@Param("personId") UUID personId);

    interface DayBucketProjection {
        LocalDate getDay();
        Long getCount();
        UUID getPhotoId();
        String getFileHash();
    }

    // One-time backfill for rows that existed before groupedDay was introduced - ddl-auto:update
    // only adds the new column, it doesn't populate it. Idempotent (guarded by the IS NULL
    // check), so safe to run unconditionally on every boot; see IPhotoApplication's
    // backfillGroupedDay CommandLineRunner. @Transactional directly on the method (rather than
    // relying on a caller's transaction) since @Modifying queries need one and the
    // CommandLineRunner that calls this isn't itself transactional.
    @Modifying
    @Transactional
    @Query(value = "UPDATE photos SET grouped_day = CAST(date_taken AS date) WHERE grouped_day IS NULL AND date_taken IS NOT NULL", nativeQuery = true)
    int backfillGroupedDay();
}