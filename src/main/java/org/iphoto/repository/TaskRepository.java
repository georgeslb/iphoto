package org.iphoto.repository;

import org.iphoto.entities.TaskEntity;
import org.iphoto.entities.UserEntity; // Import UserEntity
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
    
    @Query("SELECT t FROM TaskEntity t WHERE t.status = :status AND (t.executeAt IS NULL OR t.executeAt <= :now) and t.photoId in (:photosIds) AND t.user = :user")
    List<TaskEntity> findExecutableTasksByUser(String status, LocalDateTime now, List<UUID> photosIds, UserEntity user);

    @Query("SELECT t FROM TaskEntity t WHERE t.status = :status AND (t.executeAt IS NULL OR t.executeAt <= :now) AND t.user = :user")
    List<TaskEntity> findExecutableTasksByUser(String status, LocalDateTime now, UserEntity user);

    List<TaskEntity> findByUserAndPhotoIdAndTaskTypeIn(UserEntity user, UUID id, Set<String> taskTypes);

    // For global tasks (e.g., face recognition that might process all users' photos if not user-scoped)
    @Query("SELECT t FROM TaskEntity t WHERE t.status = :status AND (t.executeAt IS NULL OR t.executeAt <= :now) AND t.user IS NULL")
    List<TaskEntity> findGlobalExecutableTasks(String status, LocalDateTime now);

    // Find by ID and user
    Optional<TaskEntity> findByIdAndUser(UUID id, UserEntity user);
}