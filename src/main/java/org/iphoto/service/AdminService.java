package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.SetUtils;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.TaskEntity;
import org.iphoto.entities.UserEntity; // Import UserEntity
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.TaskRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminService {

    private final TaskManagerService taskManagerService;
    private final PhotoRepository photoRepository;
    private final TaskRepository taskRepository;

    public void activateFaceRecognition() { // Added UserEntity parameter
        // This will create a global task, but the processing of this task will need to be user-aware
        // or it will trigger face recognition for all photos, which might not be desired for user isolation.
        // For now, we'll assume the task processing logic (TaskManagerService) will handle user context.
        taskManagerService.createTaskEntity(TaskType.FACE_RECOGNITION, null, null, LocalDateTime.now());
    }

    public void updateImageTaskToDo() { // Added UserEntity parameter
        Set<String> taskTypes = Stream.of(TaskType.values()).filter(TaskType::isCanBeReplayed)
                .map(Enum::name).collect(Collectors.toSet());
        Slice<PhotoEntity> photosSlice;

        Pageable pageable = PageRequest.of(0, 100);
        do {
            // Filter photos by user
            photosSlice = photoRepository.findAll(pageable); // Now findByUser exists in PhotoRepository
            photosSlice.getContent().forEach(photo -> {
                List<TaskEntity> photosTasks = taskRepository.findByPhotoIdAndTaskTypeIn( photo.getId(), taskTypes);
                photosTasks.stream().filter(photoTask -> !photoTask.getStatus().equals(TaskStatus.COMPLETED)
                        && !photoTask.getExecuteAt().isAfter(LocalDateTime.now())).forEach(photoTask -> {
                            photoTask.setStatus(TaskStatus.PENDING);
                            photoTask.setErrorMessage(null);
                            photoTask.setExecuteAt(LocalDateTime.now().plusMinutes(1));
                });


                // get all task type that was not yet executed
                Set<String> tasksToAdd = SetUtils.disjunction(taskTypes, photosTasks.stream().map(TaskEntity::getTaskType).collect(Collectors.toSet()));
                tasksToAdd.forEach(taskType -> {
                    // execute task in the future depending on order if order is greater it will execute later
                    taskManagerService.createTaskEntity(TaskType.valueOf(taskType), photo.getId(), null, LocalDateTime.now().plusMinutes(TaskType.valueOf(taskType).getOrder()));
                });
            });
            pageable = photosSlice.nextPageable();

        } while (photosSlice.hasNext());
    }
}