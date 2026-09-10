package org.iphoto.repository;

import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.FaceStatus;
import org.iphoto.entities.PhotoEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
    //
    // Explicit space_id filter, NOT @MainSpaceLimited: groupFacesForUser (the only caller) runs on
    // TaskWorkerManager's own dedicated heavy-task thread pool, where MainSpaceAspectConfiguration
    // deliberately treats @MainSpaceLimited as a no-op (see its own comment on backend-task*/
    // heavy-task* thread names) - an annotation that LOOKS like it scopes results but silently
    // doesn't there was exactly what let genuinely different users' faces get mixed into the same
    // candidate cluster/person during face grouping. Passing the space explicitly instead is
    // correct regardless of which thread this runs on (see PersonRepository.findAllBySpaceId's
    // matching comment).
    @Query("SELECT f FROM FaceEntity f JOIN FETCH f.photo p WHERE f.person IS NULL AND p.space.id = :spaceId ORDER BY p.dateTaken ASC")
    Slice<FaceEntity> findByPersonIsNullAndSpaceIdFetchPhoto(@Param("spaceId") UUID spaceId, Pageable pageable);

    List<FaceEntity> findByPhotoId(UUID photoId);

    Page<FaceEntity> findByPersonId(UUID personId, Pageable pageable);

    List<FaceEntity> findByPersonIdAndPhotoId(UUID personId, UUID photoId);

    void deleteByPhotoId(PhotoEntity photo);

    // Admin > Tasks page "Restart person extraction" button (AdminService#restartPersonRecognition):
    // detaches every face from its person and flips it back to NEW so the next FACE_RECOGNITION run
    // reconsiders the whole set from scratch - the faces themselves are kept (they're the expensive
    // part: detection + embedding), only the grouping is thrown away. Must run BEFORE deleting the
    // people rows (faces.person_id FK). clearAutomatically for the same reason as
    // TaskRepository#resetAllToPendingByTaskType. Returns the row count.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FaceEntity f SET f.person = null, f.status = :status")
    int detachAllFromPersonsAndResetStatus(@Param("status") FaceStatus status);
}