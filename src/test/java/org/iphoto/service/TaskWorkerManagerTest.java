package org.iphoto.service;

import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.TaskEntity;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;
import org.iphoto.repository.TaskRepository;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Direct service-level test - TaskWorkerManager has no HTTP endpoint of its own; every other test
// in this suite already calls pollPendingTasks() as a helper (see AbstractIntegrationTest), so
// this class focuses on asserting its actual effects plus the two methods nothing else exercises
// (pollHeavyTasks/reclaimStuckProcessingTasks), and shutdown() last.
//
// @DirtiesContext(AFTER_CLASS): shutdown() really does shut down the shared
// light/duplication/face-detection/face-recognition thread pools this whole suite's cached
// Spring context reuses (see AbstractIntegrationTest's own comment on why that context is
// shared) - without this, every test class that happens to run after this one in the same JVM
// fork would find those pools already shut down. This forces Spring to discard the cached
// context afterward instead, so anything running later simply gets a fresh one.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TaskWorkerManagerTest extends AbstractIntegrationTest {

    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    @Qualifier("duplicationThreadPoolExecutor")
    private ThreadPoolTaskExecutor duplicationThreadPoolExecutor;

    @Test
    @Order(1)
    void pollPendingTasksDispatchesAPendingLightTaskUntilItReachesATerminalStatus() throws Exception {
        TaskEntity task = taskRepository.save(TaskEntity.builder()
                .photoId(UUID.randomUUID()).taskType(TaskType.HASH_IMAGE).status(TaskStatus.PENDING.name()).build());

        taskWorkerManager.pollPendingTasks();

        TaskStatus status = TaskStatus.PENDING;
        for (int attempt = 0; attempt < 40 && status != TaskStatus.COMPLETED && status != TaskStatus.FAILED; attempt++) {
            status = taskRepository.findById(task.getId()).orElseThrow().getStatus();
            if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED) break;
            Thread.sleep(250);
        }
        // HASH_IMAGE against a photoId with no real PhotoEntity row simply finds nothing to do
        // (see DuplicationServiceImpl#hashImage's findById.ifPresent) and completes - the point
        // here is confirming the task actually got PICKED UP and moved off PENDING at all.
        assertThat(status).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    @Order(2)
    void pollHeavyTasksOnlyDispatchesOnceHeavyTasksAreActivated() throws Exception {
        MockHttpSession session = loginAsAdmin();
        // Not assuming a starting value - ACTIVATE_HEAVY_TASKS is one global row, updated in
        // place (see AdminServiceTest's own comment on the same issue), so another test class
        // sharing this suite's database may have already activated it before this one runs.
        mockMvc.perform(post("/api/admin/heavyTasks").session(session).contentType(APPLICATION_JSON).content("{\"activate\":false}"))
                .andExpect(status().isOk());
        TaskEntity task = taskRepository.save(TaskEntity.builder()
                .photoId(UUID.randomUUID()).taskType(TaskType.CHECK_DUPLICATION).status(TaskStatus.PENDING.name()).build());

        taskWorkerManager.pollHeavyTasks();
        Thread.sleep(300);
        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.PENDING);

        mockMvc.perform(post("/api/admin/heavyTasks").session(session).contentType(APPLICATION_JSON).content("{\"activate\":true}"))
                .andExpect(status().isOk());
        taskWorkerManager.pollHeavyTasks();

        TaskStatus finalStatus = TaskStatus.PENDING;
        for (int attempt = 0; attempt < 40 && finalStatus == TaskStatus.PENDING; attempt++) {
            finalStatus = taskRepository.findById(task.getId()).orElseThrow().getStatus();
            if (finalStatus != TaskStatus.PENDING) break;
            Thread.sleep(250);
        }
        assertThat(finalStatus).isNotEqualTo(TaskStatus.PENDING);
    }

    @Test
    @Order(3)
    void reclaimStuckProcessingTasksResetsATaskStuckProcessingForOverAnHour() {
        TaskEntity task = taskRepository.save(TaskEntity.builder()
                .photoId(UUID.randomUUID()).taskType(TaskType.HASH_IMAGE).status(TaskStatus.PROCESSING.name()).build());
        // updated_at is stamped by @PreUpdate on every save() - backdate it directly so this task
        // looks like it's been stuck for over an hour, the condition reclaimStuckProcessingTasks
        // actually checks for.
        jdbcTemplate.update("UPDATE tasks SET updated_at = ? WHERE id = ?", LocalDateTime.now().minusHours(2), task.getId());

        taskWorkerManager.reclaimStuckProcessingTasks();

        TaskEntity reclaimed = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(reclaimed.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(reclaimed.getExecuteAt()).isAfter(LocalDateTime.now());
    }

    @Test
    @Order(4)
    void shutdownStopsTheDedicatedThreadPools() {
        taskWorkerManager.shutdown();

        // duplicationThreadPoolExecutor, not lightThreadPoolExecutor - the light pool runs on
        // virtual threads (see ThreadPoolConfiguration), which don't back a real
        // java.util.concurrent.ThreadPoolExecutor to inspect the same way.
        assertThat(duplicationThreadPoolExecutor.getThreadPoolExecutor().isShutdown()).isTrue();
    }
}
