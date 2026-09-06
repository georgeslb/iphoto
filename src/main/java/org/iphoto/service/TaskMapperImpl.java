package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.iphoto.entities.TaskEntity;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;
import org.iphoto.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true)
public class TaskMapperImpl implements TaskMapper {
    private TaskRepository taskRepository;
    private TaskManagerService taskManagerService;

    @Override
    public Set<TaskEntity> updateTasksToAction(Action action, UUID photoId) {
        switch (action) {
            case UPLOADED_PHOTO_TO_MAIN  -> {

            }
            case ON_EDIT_PHOTO_ACTION, ON_CREATE_PHOTO_ACTION  -> {
                taskManagerService.createTaskEntity(TaskType.HASH_IMAGE, photoId, null,  null);
                taskManagerService.createTaskEntity(TaskType.ISOLATE_FACES, photoId, null,  null);
            }
            case AFTER_PHOTO_HASH  -> {
                taskManagerService.createTaskEntity(TaskType.CHECK_DUPLICATION, photoId, null, null);
            }
            case PHOTO_PERMANENTLY_DELETED -> {
                List<TaskEntity> pendingTasks = taskRepository.findExecutableTasks(TaskStatus.PENDING.name(), LocalDateTime.now().plusYears(1), List.of(photoId));
                taskRepository.deleteAll(pendingTasks);
            }

            case PHOTO_MOVED_TO_GARBAGE ->  {
                taskManagerService
                        .createTaskEntity(TaskType.PERMANENT_DELETE, photoId, null, LocalDateTime.now().plusDays(30));

            }
            case PHOTO_REMOVED_FROM_GARBAGE -> {
                List<TaskEntity> pendingDeletes = taskRepository.findExecutableTasks(TaskStatus.PENDING.name(), LocalDateTime.now().plusYears(1), Collections.singletonList(photoId));
                for (TaskEntity task : pendingDeletes) {
                    if (TaskType.PERMANENT_DELETE.equals(task.getTaskType()) && photoId != null) {
                        task.setStatus(TaskStatus.IGNORED);
                        task.setErrorMessage("Asset was restored by user, permanent delete task cancelled.");
                        taskManagerService.updateTask(task);
                    }
                }
            }
        }

        return null;
    }
}
