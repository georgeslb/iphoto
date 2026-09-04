package org.iphoto.repository;

import org.iphoto.entities.AlbumEntity;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PhotoEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface PhotoRepository extends JpaRepository<PhotoEntity, UUID> {

    Page<PhotoEntity> findBySpaceIdAndIsGarbageFalse(UUID spaceId, Pageable pageable);
    
    Page<PhotoEntity> findBySpaceIdInAndIsGarbageTrue(Set<UUID> spaceIds, Pageable pageable);


    Page<PhotoEntity> findByIsGarbageFalseAndAlbums(Set<AlbumEntity> albums, Pageable pageable);

    Page<PhotoEntity> findByFacesPerson(PersonEntity person, Pageable pageable);

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

    // Backs "jump to month" and continued older-direction cursor paging after one (Memories / the
    // timeline browse list, see LibraryTimeline.jsx, and PhotoService.getPhotos'
    // beforeExclusive overload): the caller passes either the first instant of the month AFTER
    // the target one (the initial jump), or the last-loaded photo's own dateTaken (continued
    // paging), as `cutoff` — either way the returned dateTaken-desc feed starts exactly there.
    // Same shape/ordering as findBySpaceIdAndIsGarbageFalse, just with an extra upper bound.
    Page<PhotoEntity> findBySpaceIdAndIsGarbageFalseAndDateTakenLessThan(UUID spaceId, LocalDateTime cutoff, Pageable pageable);

    // The mirror-image "newer" direction — back-scrolling above a jumped-to point (see
    // PhotoService.getPhotosAfter). Pageable here must sort dateTaken ASCENDING (the caller
    // builds it that way) so LIMIT actually takes the photos *closest* to the cursor, not the
    // very oldest ones in the whole space; the service reverses the page's content back to the
    // usual newest-first order before it's returned.
    Page<PhotoEntity> findBySpaceIdAndIsGarbageFalseAndDateTakenGreaterThan(UUID spaceId, LocalDateTime cutoff, Pageable pageable);

    // The Library "month/year index" that Memories and the browse-by-month list both read from
    // (see TimelineMonthDTO) — one row per calendar month that actually has a non-garbage photo,
    // newest first, each carrying one RANDOM photo from that month as a cover thumbnail (a fresh
    // pick on every call — deliberately not deterministic, e.g. always-the-most-recent, since the
    // point of a Memories thumbnail is "look what turned up back then", not always the same shot).
    // Native SQL + window functions (not portable JPQL) because picking "one random row per group"
    // alongside its count needs them; this app is Postgres-only in practice (application.yml
    // hardcodes the driver), so that's an acceptable trade here.
    @Query(value = "select year, month, count, photo_id, file_hash from (" +
            "  select" +
            "    cast(extract(year from p.date_taken) as integer) as year," +
            "    cast(extract(month from p.date_taken) as integer) as month," +
            "    count(*) over (partition by extract(year from p.date_taken), extract(month from p.date_taken)) as count," +
            "    p.id as photo_id," +
            "    p.file_hash as file_hash," +
            "    row_number() over (partition by extract(year from p.date_taken), extract(month from p.date_taken) order by random()) as rn" +
            "  from photos p" +
            "  where p.space_id = :spaceId and p.is_garbage = false and p.date_taken is not null" +
            ") months_with_cover" +
            " where rn = 1" +
            " order by year desc, month desc", nativeQuery = true)
    List<TimelineMonthProjection> findMonthlyPhotoCountsWithCoverBySpaceId(@Param("spaceId") UUID spaceId);

    // Row shape for findMonthlyPhotoCountsWithCoverBySpaceId — a native query, so this is matched
    // by column label (case/underscore-insensitive), not by a JPQL constructor expression.
    interface TimelineMonthProjection {
        Integer getYear();
        Integer getMonth();
        Long getCount();
        UUID getPhotoId();
        String getFileHash();
    }
}