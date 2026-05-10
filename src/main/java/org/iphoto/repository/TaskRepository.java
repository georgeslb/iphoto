package org.iphoto.repository;

import org.iphoto.model.TaskEntity;
import org.iphoto.model.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface TaskRepository extends JpaRepository<TaskEntity, UUID> {
    
    @Query("SELECT t FROM TaskEntity t WHERE t.status = :status AND (t.executeAt IS NULL OR t.executeAt <= :now)")
    List<TaskEntity> findExecutableTasks(String status, LocalDateTime now);

    default List<TaskEntity> findExecutableTasks(TaskStatus status, LocalDateTime now) {
        return findExecutableTasks(status.name(), now);
    }
}
