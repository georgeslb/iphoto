package org.iphoto.service;

import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.TaskEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;
import org.iphoto.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Direct service-level test - TaskManagerService has no HTTP endpoint of its own (TaskController
// only exposes TaskService#getTask); every method here is exercised by autowiring the interface
// directly and asserting against the real (H2-backed) tasks table.
class TaskManagerServiceTest extends AbstractIntegrationTest {

    @Autowired
    private TaskManagerService taskManagerService;
    @Autowired
    private UserRepository userRepository;

    @Test
    void createTaskEntityWithNoUserPersistsAPendingTaskWithNoUserId() {
        UUID photoId = UUID.randomUUID();

        TaskEntity task = taskManagerService.createTaskEntity(TaskType.HASH_IMAGE, photoId, "payload", null);

        assertThat(task.getId()).isNotNull();
        assertThat(task.getPhotoId()).isEqualTo(photoId);
        assertThat(task.getUserId()).isNull();
        assertThat(task.getTaskType()).isEqualTo(TaskType.HASH_IMAGE);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void createTaskEntityWithAUserPersistsTheUserId() {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        UUID photoId = UUID.randomUUID();

        TaskEntity task = taskManagerService.createTaskEntity(admin, TaskType.CHECK_DUPLICATION, photoId, null, LocalDateTime.now().plusMinutes(5));

        assertThat(task.getUserId()).isEqualTo(admin.getId());
        assertThat(task.getExecuteAt()).isNotNull();
    }

    @Test
    void createTaskEntityImmediatelyWithNoUserFlushesSynchronously() {
        UUID photoId = UUID.randomUUID();

        TaskEntity task = taskManagerService.createTaskEntityImmediately(TaskType.ISOLATE_FACES, photoId, null, null);

        assertThat(task.getId()).isNotNull();
        assertThat(taskManagerService.getTask(task.getId())).isPresent();
    }

    @Test
    void createTaskEntityImmediatelyWithAUserPersistsTheUserId() {
        UserEntity admin = userRepository.findByUsername("admin").orElseThrow();
        UUID photoId = UUID.randomUUID();

        TaskEntity task = taskManagerService.createTaskEntityImmediately(admin, TaskType.ISOLATE_FACES, photoId, null, null);

        assertThat(task.getUserId()).isEqualTo(admin.getId());
    }

    @Test
    void getTaskFindsAPreviouslyCreatedTask() {
        TaskEntity created = taskManagerService.createTaskEntity(TaskType.HASH_IMAGE, UUID.randomUUID(), null, null);

        assertThat(taskManagerService.getTask(created.getId())).isPresent().get()
                .extracting(TaskEntity::getId).isEqualTo(created.getId());
        assertThat(taskManagerService.getTask(UUID.randomUUID())).isEmpty();
    }

    @Test
    void updateTaskPersistsChangesMadeToAnAlreadyLoadedTask() {
        TaskEntity task = taskManagerService.createTaskEntity(TaskType.HASH_IMAGE, UUID.randomUUID(), null, null);

        task.setStatus(TaskStatus.COMPLETED);
        taskManagerService.updateTask(task);

        assertThat(taskManagerService.getTask(task.getId())).isPresent().get()
                .extracting(TaskEntity::getStatus).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void deleteTaskEntityRemovesTheRow() {
        TaskEntity task = taskManagerService.createTaskEntityImmediately(TaskType.HASH_IMAGE, UUID.randomUUID(), null, null);

        taskManagerService.deleteTaskEntity(task);

        assertThat(taskManagerService.getTask(task.getId())).isEmpty();
    }
}
