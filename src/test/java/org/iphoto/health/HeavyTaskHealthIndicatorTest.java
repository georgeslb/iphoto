package org.iphoto.health;

import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.TaskEntity;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;
import org.iphoto.repository.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Regression coverage for the health signal added after the 2026-09-12 production incident (see
// HeavyTaskHealthIndicator's own comment / TODO.md) - a heavy task PROCESSING far longer than any
// legitimate run should must flip /actuator/health DOWN, but only for the monitored heavy types,
// and only once it's actually old enough to be suspicious.
class HeavyTaskHealthIndicatorTest extends AbstractIntegrationTest {

    @Autowired
    private HeavyTaskHealthIndicator healthIndicator;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // health() scans every PROCESSING heavy task in this suite's shared database (see
    // AbstractIntegrationTest's own comment on why it's shared across the whole run), so a "stuck"
    // fixture left behind by one test would otherwise leak into every other test's assertion here
    // (including a completely unrelated test class, if one is ever added) - tracked and cleaned up
    // after each test rather than left sitting there PROCESSING forever.
    private final List<UUID> createdTaskIds = new ArrayList<>();

    @AfterEach
    void cleanUpCreatedTasks() {
        createdTaskIds.forEach(taskRepository::deleteById);
        createdTaskIds.clear();
    }

    private TaskEntity saveProcessingTask(TaskType taskType) {
        TaskEntity task = taskRepository.save(TaskEntity.builder()
                .photoId(UUID.randomUUID())
                .taskType(taskType)
                .status(TaskStatus.PROCESSING.name())
                .build());
        createdTaskIds.add(task.getId());
        return task;
    }

    // Bypasses TaskEntity's own @PreUpdate (which would just reset updatedAt back to now on any
    // normal save) - same technique TaskWorkerManagerTest already uses to simulate an old task.
    private void backdateUpdatedAt(UUID taskId, LocalDateTime updatedAt) {
        jdbcTemplate.update("UPDATE tasks SET updated_at = ? WHERE id = ?", updatedAt, taskId);
    }

    @Test
    void reportsUpWhenNoHeavyTaskHasBeenProcessingTooLong() {
        TaskEntity recent = saveProcessingTask(TaskType.FACE_RECOGNITION);
        backdateUpdatedAt(recent.getId(), LocalDateTime.now().minusMinutes(2));

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsDownWhenAFaceRecognitionTaskHasBeenProcessingTooLong() {
        TaskEntity stuck = saveProcessingTask(TaskType.FACE_RECOGNITION);
        backdateUpdatedAt(stuck.getId(), LocalDateTime.now().minusMinutes(20));

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey(stuck.getId().toString());
    }

    @Test
    void ignoresNonHeavyTaskTypesEvenIfStuckProcessingForALongTime() {
        TaskEntity stuckButUnmonitored = saveProcessingTask(TaskType.ISOLATE_FACES);
        backdateUpdatedAt(stuckButUnmonitored.getId(), LocalDateTime.now().minusMinutes(20));

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }
}
