package org.iphoto.repository;

import org.iphoto.entities.TaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface TaskRepository extends JpaRepository<TaskEntity, UUID> {
    
    @Query("SELECT t FROM TaskEntity t WHERE t.status = :status AND (t.executeAt IS NULL OR t.executeAt <= :now) and t.photoId in (:photosIds)")
    List<TaskEntity> findExecutableTasks(String status, LocalDateTime now, List<UUID> photosIds);

    @Query("SELECT t FROM TaskEntity t WHERE t.status = :status AND (t.executeAt IS NULL OR t.executeAt <= :now)")
    List<TaskEntity> findExecutableTasks(String status, LocalDateTime now);
}
