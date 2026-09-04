package org.iphoto.repository;

import org.springframework.data.domain.Limit;
import org.iphoto.entities.TaskEntity;
import org.iphoto.model.TaskType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
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
    // only ever looks at PENDING tasks, so a task left in PROCESSING when its worker thread dies
    // mid-task (app restart, JVM crash - see specification.md/TODO.md for a real case this fixed)
    // is otherwise never picked up again. updatedAt is used as "when processing started" - see
    // TaskEntity's own @PreUpdate, which bumps it the moment pollPendingTasks flips a task to
    // PROCESSING.
    @Query("SELECT t FROM TaskEntity t WHERE t.status = :status AND t.updatedAt < :before")
    List<TaskEntity> findByStatusAndUpdatedAtBefore(String status, LocalDateTime before);
}