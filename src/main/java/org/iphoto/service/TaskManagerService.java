package org.iphoto.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.TaskEntity;
import org.iphoto.entities.UserEntity; // Import UserEntity
import org.iphoto.model.TaskType;
import org.iphoto.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@AllArgsConstructor
@Slf4j
public class TaskManagerService {

    private final TaskRepository taskRepository;

    public void updateTask(TaskEntity task) {
        taskRepository.save(task);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<TaskEntity> getTask(UUID taskId) {
        return taskRepository.findById(taskId);
    }

    public TaskEntity createTaskEntity(TaskType taskType, UUID photoId,
                                       String payload,
                                       LocalDateTime executionDateTime) {
        return createTaskEntity(null, taskType, photoId, payload, executionDateTime);
    }

    public TaskEntity createTaskEntity(UserEntity user, TaskType taskType, UUID photoId,
                                       String payload,
                                       LocalDateTime executionDateTime) {
        TaskEntity task = TaskEntity.builder()
                .user(user)
                .taskType(taskType.name())
                .photoId(photoId)
                .payload(payload)
                .executeAt(executionDateTime)
                .build();
        return taskRepository.save(task);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TaskEntity createTaskEntityImmediately(TaskType taskType, UUID photoId,
                                       String payload,
                                       LocalDateTime executionDateTime) {
        return createTaskEntityImmediately(null, taskType, photoId, payload, executionDateTime);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TaskEntity createTaskEntityImmediately(UserEntity user, TaskType taskType, UUID photoId,
                                       String payload,
                                       LocalDateTime executionDateTime) {
        TaskEntity task = TaskEntity.builder()
                .user(user)
                .taskType(taskType.name())
                .photoId(photoId)
                .payload(payload)
                .executeAt(executionDateTime)
                .build();
        return taskRepository.saveAndFlush(task);
    }

    public void deleteTaskEntity(TaskEntity task) {
        taskRepository.delete(task);
    }

}