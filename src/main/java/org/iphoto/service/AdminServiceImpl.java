package org.iphoto.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.SetUtils;
import org.iphoto.entities.FaceStatus;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.TaskEntity;
import org.iphoto.model.StorageMigrationSummaryDTO;
import org.iphoto.model.TaskAdminDTO;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;
import org.iphoto.repository.FaceRepository;
import org.iphoto.repository.PersonEmbeddingBucketRepository;
import org.iphoto.repository.PersonMergeSuggestionRepository;
import org.iphoto.repository.PersonRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.TaskRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminServiceImpl implements AdminService {

    private final TaskManagerService taskManagerService;
    private final PhotoRepository photoRepository;
    private final TaskRepository taskRepository;
    private final StorageMigrationService storageMigrationService;
    private final ObjectMapper objectMapper;
    private final FaceRepository faceRepository;
    private final PersonRepository personRepository;
    private final PersonEmbeddingBucketRepository personEmbeddingBucketRepository;
    private final PersonMergeSuggestionRepository personMergeSuggestionRepository;

    // Exactly one ACTIVATE_HEAVY_TASKS row ever exists - updated in place on every toggle, not a
    // new row per click, so isHeavyTasksActivated never has to reason about "which one is current."
    // Reset to PENDING/executeAt=now on update so the light poller still picks it up and marks it
    // COMPLETED again (same lifecycle every task goes through - see TaskWorkerManager's
    // ACTIVATE_HEAVY_TASKS branch), even though its terminal status itself doesn't matter to
    // isHeavyTasksActivated below (that reads the row regardless of status).
    @Override
    public void activateHeavyTasks(boolean activate) {
        String payload = writePayload(activate);
        TaskEntity task = taskRepository.findFirstByTaskTypeOrderByCreatedAtDesc(TaskType.ACTIVATE_HEAVY_TASKS).orElse(null);
        if (task != null) {
            task.setPayload(payload);
            task.setStatus(TaskStatus.PENDING);
            task.setExecuteAt(LocalDateTime.now());
            task.setErrorMessage(null);
            taskRepository.save(task);
        } else {
            taskManagerService.createTaskEntity(TaskType.ACTIVATE_HEAVY_TASKS, null, payload, LocalDateTime.now());
        }
    }

    @Override
    public boolean isHeavyTasksActivated() {
        return taskRepository.findFirstByTaskTypeOrderByCreatedAtDesc(TaskType.ACTIVATE_HEAVY_TASKS)
                .map(task -> {
                    try {
                        return objectMapper.readTree(task.getPayload()).path("activate").asBoolean(false);
                    } catch (Exception e) {
                        log.warn("Could not read 'activate' from ACTIVATE_HEAVY_TASKS task {} payload", task.getId(), e);
                        return false;
                    }
                })
                .orElse(false); // No toggle ever created yet - opt-in by design, not opt-out.
    }

    private String writePayload(boolean activate) {
        try {
            return objectMapper.writeValueAsString(Map.of("activate", String.valueOf(activate)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public StorageMigrationSummaryDTO migrateFlatFilesToOrganizedStorage() {
        return storageMigrationService.migrateAllFlatPhotos();
    }

    @Override
    public void updateImageTaskToDo() {
        // Note: ACTIVATE_HEAVY_TASKS is canBeReplayed=false specifically so it's never included
        // here - it's a global control record (photoId always null), not a per-photo task type;
        // see its own enum comment.
        Set<TaskType> taskTypes = Stream.of(TaskType.values()).filter(TaskType::isCanBeReplayed)
                .collect(Collectors.toSet());
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
                Set<TaskType> tasksToAdd = SetUtils.disjunction(taskTypes, photosTasks.stream().map(TaskEntity::getTaskType).collect(Collectors.toSet()));
                tasksToAdd.forEach(taskType -> {
                    // execute task in the future depending on order if order is greater it will execute later
                    log.info("Creating new task {} for photo {}", taskType, photo.getId());
                    taskManagerService.createTaskEntity(taskType, photo.getId(), null, LocalDateTime.now().plusMinutes(taskType.getOrder()));
                });
            });
            pageable = photosSlice.nextPageable();

        } while (photosSlice.hasNext());
    }

    @Override
    public Page<TaskAdminDTO> getTasks(TaskStatus status, TaskType taskType, Pageable pageable) {
        String statusName = status != null ? status.name() : null;
        return taskRepository.findByOptionalStatusAndTaskType(statusName, taskType, pageable)
                .map(TaskAdminDTO::from);
    }

    @Override
    public TaskAdminDTO restartTask(UUID taskId) {
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new NoSuchElementException("no task found with id " + taskId));
        resetToPending(task);
        return TaskAdminDTO.from(taskRepository.save(task));
    }

    @Override
    public int restartTasks(List<UUID> taskIds) {
        List<TaskEntity> tasks = taskRepository.findAllById(taskIds);
        tasks.forEach(this::resetToPending);
        taskRepository.saveAll(tasks);
        return tasks.size();
    }

    @Override
    @Transactional
    public int restartFaceExtraction() {
        return taskRepository.resetAllToPendingByTaskType(TaskType.ISOLATE_FACES, LocalDateTime.now());
    }

    @Override
    @Transactional
    public int restartDuplicationDetection() {
        return taskRepository.resetAllToPendingByTaskType(TaskType.CHECK_DUPLICATION, LocalDateTime.now());
    }

    @Override
    @Transactional
    public Map<String, Integer> restartPersonRecognition() {
        // Order matters for FKs: detach faces (faces.person_id) and drop the person-owned caches
        // (person_embedding_buckets.person_id, person_merge_suggestions.person_a/b_id) BEFORE the
        // people rows themselves. deleteAllInBatch = one DELETE per table, no per-row lifecycle
        // callbacks - PersonEntity's @PreRemove face-nulling is replaced by the bulk detach above.
        int facesReset = faceRepository.detachAllFromPersonsAndResetStatus(FaceStatus.NEW);
        personEmbeddingBucketRepository.deleteAllInBatch();
        personMergeSuggestionRepository.deleteAllInBatch();
        int personsDeleted = (int) personRepository.count();
        personRepository.deleteAllInBatch();

        // FACE_RECOGNITION is one perpetual, self-rescheduling row (see IPhotoApplication's
        // bootstrap) - reset the existing one to run now, or bootstrap it if it somehow isn't there.
        taskRepository.findFirstByTaskTypeOrderByCreatedAtDesc(TaskType.FACE_RECOGNITION)
                .ifPresentOrElse(
                        task -> {
                            resetToPending(task);
                            taskRepository.save(task);
                        },
                        () -> taskManagerService.createTaskEntity(TaskType.FACE_RECOGNITION, null, null, LocalDateTime.now()));

        log.info("Person recognition restarted by admin: deleted {} people, detached {} faces", personsDeleted, facesReset);
        return Map.of("personsDeleted", personsDeleted, "facesReset", facesReset);
    }

    // Manual admin override of a task's status - the same reset updateImageTaskToDo above and
    // TaskWorkerManagerImpl#reclaimStuckProcessingTasks already apply automatically, just
    // triggered on demand from the Tasks page instead of by a schedule/replay pass.
    // Deliberately unconditional on the task's CURRENT status (including QUEUED/PROCESSING,
    // unlike updateImageTaskToDo's COMPLETED-only exclusion above) - an admin who can see this
    // exact task in the UI and chooses to restart it is trusted to know it's actually stuck, not
    // still genuinely running. Worst case if it IS still running is that task executing twice,
    // which every task type here already has to tolerate anyway (reclaimStuckProcessingTasks can
    // race a slow-but-alive worker the exact same way, hourly, with no human judgment involved at
    // all).
    private void resetToPending(TaskEntity task) {
        task.setStatus(TaskStatus.PENDING);
        task.setErrorMessage(null);
        task.setStackTrace(null);
        task.setExecuteAt(LocalDateTime.now());
    }
}
