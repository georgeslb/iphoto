package org.iphoto.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.TaskEntity;
import org.iphoto.model.TaskType;
import org.iphoto.repository.TaskRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@AllArgsConstructor
@Slf4j
public class TaskManagerService {

    private final TaskRepository taskRepository;


    public void updateTask(TaskEntity task) {
        taskRepository.save(task);
    }



    public TaskEntity createTaskEntity(TaskType taskType, UUID photoId,
                                       String payload,
                                       LocalDateTime executionDateTime) {
        TaskEntity task = new TaskEntity();
        task.setTaskType(taskType.name());
        task.setPhotoId(photoId);
        task.setPayload(payload);
        task.setExecuteAt(executionDateTime);
        return taskRepository.save(task);
    }

    public void deleteTaskEntity(TaskEntity task) {
        taskRepository.delete(task);
    }

}
