package org.iphoto.repository;

import org.iphoto.configuration.MainSpaceLimited;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PhotoEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FaceRepository extends JpaRepository<FaceEntity, UUID> {
    // JOIN FETCH photo (a @ManyToOne, so no in-memory-pagination concern the way a *-to-many fetch
    // join would have) - FaceRecognitionServiceImpl.groupFacesForUser reads every returned face's
    // photo (dateTaken for clustering, id for logging) well after this method's own short
    // transaction has closed, once per-face/per-user work each got its own independent transaction
    // (see that class's own comment) rather than one transaction spanning the whole run. Without
    // eager-fetching it here, that later access would throw LazyInitializationException - no
    // session left open to fetch it on demand at that point. Explicit countQuery since Spring
    // Data's own count-query derivation for a JOIN FETCH @Query is not reliable across versions.
    @MainSpaceLimited
    @Query(value = "SELECT f FROM FaceEntity f JOIN FETCH f.photo WHERE f.person IS NULL",
            countQuery = "SELECT COUNT(f) FROM FaceEntity f WHERE f.person IS NULL")
    Page<FaceEntity> findByPersonIsNullFetchPhoto(Pageable pageable);

    List<FaceEntity> findByPhotoId(UUID photoId);

    Page<FaceEntity> findByPersonId(UUID personId, Pageable pageable);

    List<FaceEntity> findByPersonIdAndPhotoId(UUID personId, UUID photoId);

    void deleteByPhotoId(PhotoEntity photo);
}