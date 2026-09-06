package org.iphoto.service;

import org.iphoto.entities.TaskEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.TaskType;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface TaskManagerService {

    void updateTask(TaskEntity task);

    Optional<TaskEntity> getTask(UUID taskId);

    TaskEntity createTaskEntity(TaskType taskType, UUID photoId,
                                String payload,
                                LocalDateTime executionDateTime);

    TaskEntity createTaskEntity(UserEntity user, TaskType taskType, UUID photoId,
                                String payload,
                                LocalDateTime executionDateTime);

    TaskEntity createTaskEntityImmediately(TaskType taskType, UUID photoId,
                                           String payload,
                                           LocalDateTime executionDateTime);

    TaskEntity createTaskEntityImmediately(UserEntity user, TaskType taskType, UUID photoId,
                                           String payload,
                                           LocalDateTime executionDateTime);

    void deleteTaskEntity(TaskEntity task);
}
