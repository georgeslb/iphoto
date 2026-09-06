package org.iphoto.service;

import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.TaskEntity;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;
import org.iphoto.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Direct service-level test - TaskMapper has no HTTP endpoint of its own, it's the internal
// "what tasks does this photo lifecycle event trigger" dispatcher used by PhotoService/
// PhotoUploadService/PhotoDeletionService/EditPhotoService/DuplicationService (see TaskMapper.
// Action's own javadoc). Each Action variant is exercised directly, asserting the resulting rows
// in the real tasks table rather than the return value (updateTasksToAction always returns null,
// see TaskMapperImpl).
class TaskMapperTest extends AbstractIntegrationTest {

    @Autowired
    private TaskMapper taskMapper;
    @Autowired
    private TaskRepository taskRepository;

    @Test
    void uploadedPhotoToMainIsANoOp() {
        UUID photoId = UUID.randomUUID();

        taskMapper.updateTasksToAction(TaskMapper.Action.UPLOADED_PHOTO_TO_MAIN, photoId);

        assertThat(taskRepository.findByPhotoIdAndTaskTypeIn(photoId, Set.of(TaskType.values()))).isEmpty();
    }

    @Test
    void onCreatePhotoActionQueuesHashAndIsolateFacesTasks() {
        UUID photoId = UUID.randomUUID();

        taskMapper.updateTasksToAction(TaskMapper.Action.ON_CREATE_PHOTO_ACTION, photoId);

        List<TaskEntity> tasks = taskRepository.findByPhotoIdAndTaskTypeIn(photoId, Set.of(TaskType.HASH_IMAGE, TaskType.ISOLATE_FACES));
        assertThat(tasks).extracting(TaskEntity::getTaskType).containsExactlyInAnyOrder(TaskType.HASH_IMAGE, TaskType.ISOLATE_FACES);
    }

    @Test
    void onEditPhotoActionQueuesHashAndIsolateFacesTasks() {
        UUID photoId = UUID.randomUUID();

        taskMapper.updateTasksToAction(TaskMapper.Action.ON_EDIT_PHOTO_ACTION, photoId);

        List<TaskEntity> tasks = taskRepository.findByPhotoIdAndTaskTypeIn(photoId, Set.of(TaskType.HASH_IMAGE, TaskType.ISOLATE_FACES));
        assertThat(tasks).hasSize(2);
    }

    @Test
    void afterPhotoHashQueuesACheckDuplicationTask() {
        UUID photoId = UUID.randomUUID();

        taskMapper.updateTasksToAction(TaskMapper.Action.AFTER_PHOTO_HASH, photoId);

        List<TaskEntity> tasks = taskRepository.findByPhotoIdAndTaskTypeIn(photoId, Set.of(TaskType.CHECK_DUPLICATION));
        assertThat(tasks).hasSize(1);
    }

    @Test
    void photoMovedToGarbageQueuesADelayedPermanentDeleteTask() {
        UUID photoId = UUID.randomUUID();

        taskMapper.updateTasksToAction(TaskMapper.Action.PHOTO_MOVED_TO_GARBAGE, photoId);

        List<TaskEntity> tasks = taskRepository.findByPhotoIdAndTaskTypeIn(photoId, Set.of(TaskType.PERMANENT_DELETE));
        assertThat(tasks).hasSize(1);
        assertThat(tasks.getFirst().getExecuteAt()).isAfter(LocalDateTime.now().plusDays(29));
    }

    @Test
    void photoRemovedFromGarbageCancelsAPendingPermanentDeleteTask() {
        UUID photoId = UUID.randomUUID();
        TaskEntity pendingDelete = taskRepository.save(TaskEntity.builder()
                .photoId(photoId).taskType(TaskType.PERMANENT_DELETE).status(TaskStatus.PENDING.name())
                .executeAt(LocalDateTime.now().plusDays(30)).build());

        taskMapper.updateTasksToAction(TaskMapper.Action.PHOTO_REMOVED_FROM_GARBAGE, photoId);

        TaskEntity reloaded = taskRepository.findById(pendingDelete.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.IGNORED);
        assertThat(reloaded.getErrorMessage()).isNotBlank();
    }

    @Test
    void photoPermanentlyDeletedRemovesItsExecutablePendingTasks() {
        UUID photoId = UUID.randomUUID();
        taskRepository.save(TaskEntity.builder()
                .photoId(photoId).taskType(TaskType.HASH_IMAGE).status(TaskStatus.PENDING.name()).build());

        taskMapper.updateTasksToAction(TaskMapper.Action.PHOTO_PERMANENTLY_DELETED, photoId);

        assertThat(taskRepository.findByPhotoIdAndTaskTypeIn(photoId, Set.of(TaskType.HASH_IMAGE))).isEmpty();
    }
}
