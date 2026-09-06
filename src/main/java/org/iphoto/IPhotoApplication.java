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
        // OpenCV's own internal parallel_for_ backend (used by DuplicationService's ORB/BFMatcher/
        // phash calls) defaults to spinning up its own worker-thread pool sized to
        // hardware_concurrency - and, on the pthread-only backend this prebuilt javacpp binary
        // uses (no TBB), each distinct OpenCV subsystem (ORB, BFMatcher, img_hash) turned out to
        // lazily create its OWN such pool rather than sharing one process-wide pool: confirmed
        // live via a thread dump showing ~46 OS-level "heavy-task-dupl..." threads sharing the
        // name of the single dedicated CHECK_DUPLICATION pool thread, several times more than
        // this machine's core count, despite that pool only ever running one task at a time.
        // CHECK_DUPLICATION's own concurrency is already fully governed by its dedicated
        // single-thread pool (see IPhotoProperties.HeavyTasks#duplicationThreads) - a single
        // photo-sized image comparison doesn't need OpenCV's own internal multi-threading on top
        // of that, so disable it outright rather than trying to size multiple per-subsystem pools
        // correctly. Must be set before any org.bytedeco.opencv class is used (its native library
        // loads on first reference), so as early as possible here.
        org.bytedeco.opencv.global.opencv_core.setNumThreads(1);

        SpringApplication app = new SpringApplication(IPhotoApplication.class);
        // Face detection/embedding's own CPU-thread tuning (equivalent in spirit to the OpenCV
        // call above) now lives in FaceDetectionConfiguration itself, as plain per-model Criteria
        // options (optOption("interOpNumThreads"/"intraOpNumThreads", ...)) - the ONNX Runtime
        // engine that replaced PyTorch here (see the buffalo_l model swap) takes these per-model at
        // load time, unlike PyTorch's engine-wide JVM system properties which had to be set this
        // early, before the engine initialized. Nothing needs setting here anymore for that.
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