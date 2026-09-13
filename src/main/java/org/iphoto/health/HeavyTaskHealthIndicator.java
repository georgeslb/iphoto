package org.iphoto.health;

import lombok.RequiredArgsConstructor;
import org.iphoto.entities.TaskEntity;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;
import org.iphoto.repository.TaskRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

// Surfaces on /actuator/health - see application.yml's own comment on why Actuator exists at all
// here: a real production incident (TODO.md, 2026-09-12) took hours to even diagnose because
// nothing flagged that a single heavy-task execution had been running far longer than any
// legitimate run of this codebase's own bounded algorithms ever should - the JVM looked "up",
// Docker's own healthcheck (a plain curl to "/") was passing right up until the whole app wedged
// solid, and by then it was a live-fire thread-dump investigation instead of an early warning.
//
// WARN_THRESHOLD sits well above any normal run's expected duration (groupFaces() caps itself at
// FaceRecognitionServiceImpl.MAX_UNGROUPED_FACES_PER_RUN faces per invocation) but far below the
// hour TaskWorkerManagerImpl#reclaimStuckProcessingTasks waits before treating a task as
// orphaned and resetting it - the two are complementary: this alerts fast on something that's
// probably still legitimately running, just abnormally slowly (or genuinely stuck); reclaim is
// the eventual, unconditional fallback. Only the two heavy-task types are watched - light/
// middle-charge tasks are individually small and numerous enough that any one of them running
// long isn't itself unusual the way a heavy task's dedicated single thread is.
@Component
@RequiredArgsConstructor
public class HeavyTaskHealthIndicator implements HealthIndicator {

    private static final Duration WARN_THRESHOLD = Duration.ofMinutes(15);
    private static final Set<TaskType> MONITORED_TYPES = Set.of(TaskType.FACE_RECOGNITION, TaskType.CHECK_DUPLICATION);

    private final TaskRepository taskRepository;

    @Override
    public Health health() {
        LocalDateTime now = LocalDateTime.now();
        List<TaskEntity> longRunning = taskRepository
                .findByStatusInAndUpdatedAtBefore(Set.of(TaskStatus.PROCESSING.name()), now.minus(WARN_THRESHOLD))
                .stream()
                .filter(task -> MONITORED_TYPES.contains(task.getTaskType()))
                .toList();

        if (longRunning.isEmpty()) {
            return Health.up().build();
        }

        Health.Builder builder = Health.down();
        for (TaskEntity task : longRunning) {
            long runningMinutes = Duration.between(task.getUpdatedAt(), now).toMinutes();
            builder.withDetail(task.getId().toString(),
                    "%s has been PROCESSING for %d minute(s)".formatted(task.getTaskType(), runningMinutes));
        }
        return builder.build();
    }
}
