package org.iphoto.repository;

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

    @Query("SELECT t FROM TaskEntity t WHERE t.status = :status AND (t.executeAt IS NULL OR t.executeAt <= :now)")
    List<TaskEntity> findExecutableTasks(String status, LocalDateTime now);

    // Also the re-run guard for Immich day import (see ImmichImportService.triggerDayImport):
    // an asset with any non-FAILED SYNC_FROM_IMMICH/UPLOAD_FROM_IMMICH task already recorded is still in
    // flight or already handled and must not be queued a second time; a FAILED one is exactly
    // what a retry should replace.
    List<TaskEntity> findByPhotoIdAndTaskTypeIn(UUID id, Set<TaskType> taskTypes);
}