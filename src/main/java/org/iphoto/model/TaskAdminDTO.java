package org.iphoto.model;

import org.iphoto.entities.TaskEntity;

import java.time.LocalDateTime;
import java.util.UUID;

// Admin > Tasks page (AdminController#getTasks) - richer than TaskDTO (which only ever backs the
// public single-task-status poll and deliberately exposes just id/status/photoId). Admins need
// enough to triage a stuck/failed task without opening a DB console: its type, both timestamps,
// and the error it last failed with.
public record TaskAdminDTO(
        UUID id,
        TaskType taskType,
        TaskStatus status,
        UUID photoId,
        UUID userId,
        String errorMessage,
        LocalDateTime executeAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static TaskAdminDTO from(TaskEntity task) {
        return new TaskAdminDTO(
                task.getId(),
                task.getTaskType(),
                task.getStatus(),
                task.getPhotoId(),
                task.getUserId(),
                task.getErrorMessage(),
                task.getExecuteAt(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
