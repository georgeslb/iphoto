package org.iphoto.repository;

import org.iphoto.configuration.MainSpaceLimited;
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

    // --- Day-index counts (see model/DayBucketDTO) ---
    // Plain GROUP BY count query - index-friendly on idx_photos_space_grouped_day, no sort needed.
    // No per-day cover photo here (that was computed for every day but never actually rendered
    // anywhere in the frontend) - the only place a cover/representative photo is needed is
    // Library's Memories bar, which only needs a handful of photos for a handful of specific day
    // windows (see findRandomPhotosBySpaceIdAndGroupedDayBetween below), not for every day in the
    // index.

    @Query("SELECT p.groupedDay as day, COUNT(p) as count FROM PhotoEntity p " +
            "WHERE p.space.id = :spaceId AND p.isGarbage = false AND p.groupedDay IS NOT NULL " +
            "GROUP BY p.groupedDay ORDER BY p.groupedDay DESC")
    List<DayCountProjection> countDailyPhotosBySpaceId(@Param("spaceId") UUID spaceId);

    @Query("SELECT p.groupedDay as day, COUNT(p) as count FROM PhotoEntity p JOIN p.albums a " +
            "WHERE a IN :albums AND p.isGarbage = false AND p.groupedDay IS NOT NULL " +
            "GROUP BY p.groupedDay ORDER BY p.groupedDay DESC")
    List<DayCountProjection> countDailyPhotosByAlbums(@Param("albums") Set<AlbumEntity> albums);

    // Garbage: cross-space (spaceIdIn), matching the flat endpoint this replaced.
    @Query("SELECT p.groupedDay as day, COUNT(p) as count FROM PhotoEntity p " +
            "WHERE p.space.id IN :spaceIds AND p.isGarbage = true AND p.groupedDay IS NOT NULL " +
            "GROUP BY p.groupedDay ORDER BY p.groupedDay DESC")
    List<DayCountProjection> countDailyGarbagePhotosBySpaceIds(@Param("spaceIds") Set<UUID> spaceIds);

    // Person: joined through faces instead of space_id/album_photos - deliberately no isGarbage
    // filter, matching findByFacesPerson's existing (no-filter) semantics.
    @Query("SELECT p.groupedDay as day, COUNT(p) as count FROM PhotoEntity p JOIN p.faces f " +
            "WHERE f.person.id = :personId AND p.groupedDay IS NOT NULL " +
            "GROUP BY p.groupedDay ORDER BY p.groupedDay DESC")
    List<DayCountProjection> countDailyPhotosByPersonId(@Param("personId") UUID personId);

    interface DayCountProjection {
        LocalDate getDay();
        Long getCount();
    }

    // Library's Memories bar (PhotoService.getMemories): up to `limit` random non-garbage photos
    // within a day window (unlike the DISTINCT-ON-per-many-days pattern the old per-day cover
    // query used, this only ever needs to answer for one window at a time) - fewer than `limit`,
    // or empty, if the window doesn't have that many (or any). An exact single day is just a
    // window where startDay == endDay - see MemoryOffset/IPhotoProperties.Memories.offsets for
    // where the window's bounds come from.
    @Query(value = "SELECT * FROM photos p WHERE p.space_id = :spaceId AND p.is_garbage = false " +
            "AND p.grouped_day BETWEEN :startDay AND :endDay ORDER BY random() LIMIT :limit", nativeQuery = true)
    List<PhotoEntity> findRandomPhotosBySpaceIdAndGroupedDayBetween(@Param("spaceId") UUID spaceId, @Param("startDay") LocalDate startDay, @Param("endDay") LocalDate endDay, @Param("limit") int limit);

    // Garbage's day-index equivalent - cross-space (spaceIdIn), matching the flat endpoint this
    // replaced, since a user's garbage isn't scoped to one space.
    Page<PhotoEntity> findBySpaceIdInAndIsGarbageTrueAndGroupedDay(Set<UUID> spaceIds, LocalDate groupedDay, Pageable pageable);

    Page<PhotoEntity> findByFacesPerson(PersonEntity person, Pageable pageable);

    // Person's day-index equivalent - deliberately no isGarbage filter, matching findByFacesPerson
    // above (Person view has never excluded garbage photos).
    @MainSpaceLimited
    Page<PhotoEntity> findByFacesPersonAndGroupedDay(PersonEntity person, LocalDate groupedDay, Pageable pageable);

    // Backs PersonRecognitionServiceImpl.recalculatePhotoCount - a photo with two+ faces belonging
    // to the same person (e.g. a group shot the detector matched twice) must still only count once,
    // unlike findByFacesPerson above (a plain derived join, no DISTINCT - fine for that method's
    // own paginated-listing use, not for a count meant to be an accurate "photos this person is in"
    // number).
    @Query("SELECT COUNT(DISTINCT p) FROM PhotoEntity p JOIN p.faces f WHERE f.person = :person")
    long countDistinctByFacesPerson(@Param("person") PersonEntity person);

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

    // Authorization boundary for bulk actions that read files directly off disk (bulk download,
    // see PhotoService.findOwnedByIds) - filters at the query level rather than fetching every
    // matched id then filtering in Java, so a caller passing another user's id never even loads
    // that row.
    List<PhotoEntity> findByIdInAndSpaceUserId(List<UUID> photoId, UUID userId);

    // Used by the share/guest flow to confirm a photo actually belongs to the album a share
    // link grants access to, before serving it (guest sessions never have space-filtering
    // applied, so this containment check is the real authorization boundary for them).
    Optional<PhotoEntity> findByIdAndAlbums_IdAndIsGarbageFalse(UUID id, UUID albumId);

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