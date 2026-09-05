package org.iphoto;

import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.TaskEntity;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;
import org.iphoto.properties.AdminProperties; // Import AdminProperties
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.TaskRepository;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.TaskManagerService;
import org.iphoto.service.UserService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Slf4j
@SpringBootApplication
@EnableScheduling
@EnableRetry(proxyTargetClass=true)
public class IPhotoApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(IPhotoApplication.class);
        // PyTorch's CPU backend defaults to using every available core for the tensor math inside
        // a single inference call (intra-op parallelism), independent of how many Java-level
        // worker threads are running face-detection tasks. This used to be a flat "8" regardless
        // of concurrency - fine back when ISOLATE_FACES could only ever run 1 at a time (see the
        // heavy-task-pool rework in TODO.md), but once face-detection-threads > 1 (default 3,
        // IPhotoProperties.HeavyTasks) that many *concurrent* Predictor calls would each try to
        // claim 8 native threads - up to faceDetectionThreads * 8 threads fighting over however
        // many cores the machine actually has, causing exactly the CPU thrashing/huge-usage
        // reported after that change. Divide the machine's cores across the configured
        // concurrency instead, so the total native thread budget stays proportional to what's
        // really available regardless of how face-detection-threads is tuned.
        //
        // Must be set before the DJL PyTorch engine initializes (its first Criteria.loadModel()
        // call, a @Bean in FaceDetectionConfiguration created during context refresh) - an
        // ApplicationEnvironmentPreparedEvent listener fires after application.yml is bound into
        // the Environment but before any bean (including that one) is created, which is exactly
        // the window needed to read the real configured value here.
        app.addListeners((org.springframework.context.ApplicationListener<ApplicationEnvironmentPreparedEvent>) event -> {
            int faceDetectionThreads = event.getEnvironment()
                    .getProperty("iphoto.heavy-tasks.face-detection-threads", Integer.class, 3);
            int availableProcessors = Runtime.getRuntime().availableProcessors();
            int pytorchThreads = Math.max(1, availableProcessors / faceDetectionThreads);
            System.setProperty("ai.djl.pytorch.num_threads", String.valueOf(pytorchThreads));
        });
        app.run(args);
    }

    @Bean
    CommandLineRunner initDatabase(UserRepository userRepository, UserService userService, AdminProperties adminProperties) {
        return args -> {
            if (userRepository.findByUsername(adminProperties.getUsername()).isEmpty()) {
                // Routed through UserService.createUser (not a raw UserEntity save) so the
                // bootstrapped admin gets a default MAIN space too, exactly like any user created
                // via the admin-facing "create user" API — a fresh install used to leave the
                // bootstrapped admin with no space at all, which broke upload/albums/etc.
                // (SpaceService.getDefaultSpaceForUser(admin) would return null) until a space
                // was created some other way. Found while writing integration tests against a
                // genuinely fresh database, where this had never been exercised before.
                userService.createUser(adminProperties.getUsername(), adminProperties.getPassword(), adminProperties.getRole());
            }
        };
    }

    // Backfills the groupedDay column (day-index browsing) for photos that existed before it was
    // introduced - ddl-auto:update only adds the new column on boot, it never populates existing
    // rows. Idempotent (PhotoRepository.backfillGroupedDay is itself IS NULL-guarded), so this is
    // a cheap no-op on every boot after the first; a plain CommandLineRunner bean rather than a
    // new ApplicationRunner class, matching initDatabase above as the existing precedent for
    // one-off startup tasks in this app.
    @Bean
    CommandLineRunner backfillGroupedDay(PhotoRepository photoRepository) {
        return args -> {
            int updated = photoRepository.backfillGroupedDay();
            if (updated > 0) {
                log.info("Backfilled groupedDay for {} existing photo(s)", updated);
            }
        };
    }

    // FACE_RECOGNITION perpetually reschedules itself 30s after each run (see
    // TaskWorkerManager#processTask) once started, but nothing ever creates the FIRST one anymore -
    // that used to be the admin "Activate Face Recognition" button, replaced by the heavy-tasks
    // activate/deactivate switch (see AdminService/AdminController). Bootstrap it here instead, once,
    // so face grouping keeps working without needing a manual admin trigger. Idempotent (guarded by
    // existsByTaskType), so a cheap no-op on every boot after the first.
    @Bean
    CommandLineRunner bootstrapFaceRecognition(TaskRepository taskRepository, TaskManagerService taskManagerService) {
        return args -> {
            if (!taskRepository.existsByTaskType(TaskType.FACE_RECOGNITION)) {
                taskManagerService.createTaskEntity(TaskType.FACE_RECOGNITION, null, null, LocalDateTime.now());
                log.info("Bootstrapped the initial FACE_RECOGNITION task");
            }
        };
    }

    // Complements TaskWorkerManager#reclaimStuckProcessingTasks (the ongoing, age-gated hourly
    // sweep for tasks orphaned without a restart) rather than replacing it: at this exact moment,
    // the app has just booted, so there's no "might still genuinely be running" ambiguity to wait
    // out the way the hourly sweep has to - ANY row still QUEUED (claimed by a poller, submitted,
    // but never actually started - see TaskStatus.QUEUED) or PROCESSING (started but never
    // finished) from before this JVM existed is unconditionally orphaned; nothing in-memory
    // survives a restart. Without this, every restart would leave whatever was in flight at that
    // moment dead for up to an hour before the hourly sweep's staleness threshold caught up to it.
    @Bean
    CommandLineRunner resetOrphanedTasksOnStartup(TaskRepository taskRepository) {
        return args -> {
            List<TaskEntity> orphaned = taskRepository.findByStatusIn(Set.of(TaskStatus.QUEUED.name(), TaskStatus.PROCESSING.name()));
            if (orphaned.isEmpty()) return;
            log.info("Resetting {} task(s) left QUEUED/PROCESSING from before this startup back to PENDING", orphaned.size());
            for (TaskEntity task : orphaned) {
                task.setStatus(TaskStatus.PENDING);
                task.setExecuteAt(LocalDateTime.now());
                taskRepository.save(task);
            }
        };
    }
}