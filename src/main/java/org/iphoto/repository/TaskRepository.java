package org.iphoto.repository;

import org.springframework.data.domain.Limit;
import org.iphoto.entities.TaskEntity;
import org.iphoto.model.TaskType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface TaskRepository extends JpaRepository<TaskEntity, UUID> {
    
    @Query("SELECT t FROM TaskEntity t WHERE t.status = :status AND (t.executeAt IS NULL OR t.executeAt <= :now) and t.photoId in (:photosIds)")
    List<TaskEntity> findExecutableTasks(String status, LocalDateTime now, List<UUID> photosIds);

    @Query("SELECT t FROM TaskEntity t WHERE t.status = :status AND (t.executeAt IS NULL OR t.executeAt <= :now) and t.taskType in (:types) order by t.createdAt asc")
    Set<TaskEntity> findExecutableTasks(String status, LocalDateTime now, Set<TaskType> types, Limit limit);

    // Also the re-run guard for Immich day import (see ImmichImportService.triggerDayImport):
    // an asset with any non-FAILED SYNC_FROM_IMMICH/UPLOAD_FROM_IMMICH task already recorded is still in
    // flight or already handled and must not be queued a second time; a FAILED one is exactly
    // what a retry should replace.
    List<TaskEntity> findByPhotoIdAndTaskTypeIn(UUID id, Set<TaskType> taskTypes);

    // Orphan sweep (see TaskWorkerManager.reclaimStuckProcessingTasks): findExecutableTasks above
    // only ever looks at PENDING tasks, so a task left in PROCESSING (worker thread died mid-task -
    // app restart, JVM crash, see specification.md/TODO.md for a real case this fixed) or QUEUED
    // (claimed by a poller and submitted, but the submit() itself failed/never actually entered the
    // executor - see TaskStatus.QUEUED) is otherwise never picked up again. updatedAt is used as
    // "when this status was set" - see TaskEntity's own @PreUpdate.
    @Query("SELECT t FROM TaskEntity t WHERE t.status IN (:statuses) AND t.updatedAt < :before")
    List<TaskEntity> findByStatusInAndUpdatedAtBefore(Collection<String> statuses, LocalDateTime before);

    // Startup reset (see IPhotoApplication's resetOrphanedTasksOnStartup CommandLineRunner):
    // unlike the age-gated sweep above, no staleness check is needed here - at the exact moment
    // the app boots, ANY row still PROCESSING/QUEUED from before this JVM started is unconditionally
    // orphaned (nothing in-memory survives a restart), so there's no "might still genuinely be
    // running" ambiguity to wait out the way there is during normal operation.
    List<TaskEntity> findByStatusIn(Collection<String> statuses);

    // The admin heavy-tasks on/off switch (see AdminService#isHeavyTasksActivated/
    // activateHeavyTasks): one new TaskEntity row per toggle, never updated in place, so "current
    // state" is whichever is most recent.
    Optional<TaskEntity> findFirstByTaskTypeOrderByCreatedAtDesc(TaskType taskType);

    // Bootstrap check (see IPhotoApplication's bootstrapFaceRecognition CommandLineRunner) - was
    // the perpetually-self-rescheduling FACE_RECOGNITION task ever created at all.
    boolean existsByTaskType(TaskType taskType);

    // Backs GET /api/immich/albums (see ImmichAlbumImportService#listAlbumImportStatus) - every
    // IMPORT_IMMICH_ALBUM task this user has ever queued, so the latest one per album (by photoId,
    // which carries the local AlbumEntity id for this task type) can be picked out to report that
    // album's current status.
    List<TaskEntity> findByUserIdAndTaskType(UUID userId, TaskType taskType);
}