package org.iphoto.repository;

import org.iphoto.configuration.MainSpaceLimited;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PhotoEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
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
    // session left open to fetch it on demand at that point.
    //
    // ORDER BY f.photo.dateTaken ASC - groupFacesForUser now fetches/processes a user's whole
    // ungrouped-faces backlog in chronological order (not arbitrary DB order) across every slice of
    // one run, so CandidateClusterer's capped representative sets get picked up as the timeline
    // progresses instead of whichever faces happened to come back first - see CandidateClusterer's
    // own class comment and TODO.md's "qualif-server item 5" entry for the full rationale.
    //
    // Slice, not Page - groupFacesForUser now fetches this in small CLUSTERING_SLICE_SIZE pages
    // instead of one big MAX_UNGROUPED_FACES_PER_RUN page, and a forward-only scan like that only
    // needs hasNext(), never a total count - so the previous hand-written countQuery (needed only
    // because Spring Data can't reliably derive a count query for a JOIN FETCH @Query) is gone
    // entirely, removing one extra COUNT(*) query per slice fetched.
    @MainSpaceLimited
    @Query("SELECT f FROM FaceEntity f JOIN FETCH f.photo WHERE f.person IS NULL ORDER BY f.photo.dateTaken ASC")
    Slice<FaceEntity> findByPersonIsNullFetchPhoto(Pageable pageable);

    List<FaceEntity> findByPhotoId(UUID photoId);

    Page<FaceEntity> findByPersonId(UUID personId, Pageable pageable);

    List<FaceEntity> findByPersonIdAndPhotoId(UUID personId, UUID photoId);

    void deleteByPhotoId(PhotoEntity photo);
}