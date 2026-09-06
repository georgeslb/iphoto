package org.iphoto.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.OperatingSystemMXBean;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.TaskEntity;
import org.iphoto.exception.ImmichApiException;
import org.iphoto.model.ImmichAssetImportOutcome;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;
import org.iphoto.model.UploadStatus;
import org.iphoto.repository.TaskRepository;
import org.iphoto.service.immich.ImmichAlbumImportService;
import org.iphoto.service.immich.ImmichImportService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.iphoto.model.TaskType.*;

@Service
@Slf4j
public class TaskWorkerManager {

    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final DuplicationService duplicationService;
    private final PhotoService photoService;
    private final FaceDetectionService faceDetectionService;
    private final FaceRecognitionService faceRecognitionService;
    private final ImmichImportService immichImportService;
    private final ImmichAlbumImportService immichAlbumImportService;
    private final AdminService adminService;
    private final ThreadPoolTaskExecutor lightThreadPoolExecutor;
    // One dedicated, fixed-size pool per heavy task type instead of one shared pool - see
    // IPhotoProperties.HeavyTasks/ThreadPoolConfiguration for why (each service used to also hold
    // its own internal lock capping itself to 1 concurrent run no matter how big the shared pool
    // was, and DuplicationService's lock had a real double-lock/single-unlock bug that permanently
    // jammed CHECK_DUPLICATION after its first run - both gone now that the pool size itself is
    // the only concurrency limit, per type).
    private final ThreadPoolTaskExecutor duplicationThreadPoolExecutor;
    private final ThreadPoolTaskExecutor faceDetectionThreadPoolExecutor;
    private final ThreadPoolTaskExecutor faceRecognitionThreadPoolExecutor;

    private static final Set<TaskType> LIGHT_TASK_TYPES =
            Stream.of(TaskType.values()).filter(taskType -> !taskType.isHeavy()).collect(Collectors.toSet());

    // See adjustLightConcurrencyForCpuLoad below for why this (not corePoolSize/maxPoolSize) is
    // the actual concurrency knob for the light pool.
    private static final OperatingSystemMXBean OS_BEAN =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final int LIGHT_CONCURRENCY_MIN = 10;
    private static final int LIGHT_CONCURRENCY_STEP = 10;
    private static final double CPU_LOAD_LOW_THRESHOLD = 0.5;
    private static final double CPU_LOAD_HIGH_THRESHOLD = 0.85;
    private final AtomicInteger lightConcurrencyLimit;

    public TaskWorkerManager(TaskRepository taskRepository, ObjectMapper objectMapper, TransactionTemplate transactionTemplate, DuplicationService duplicationService, PhotoService photoService, FaceDetectionService faceDetectionService, FaceRecognitionService faceRecognitionService
            , ImmichImportService immichImportService, ImmichAlbumImportService immichAlbumImportService, AdminService adminService, @Qualifier("lightThreadPoolExecutor") ThreadPoolTaskExecutor lightThreadPoolExecutor,
              @Qualifier("duplicationThreadPoolExecutor") ThreadPoolTaskExecutor duplicationThreadPoolExecutor,
              @Qualifier("faceDetectionThreadPoolExecutor") ThreadPoolTaskExecutor faceDetectionThreadPoolExecutor,
              @Qualifier("faceRecognitionThreadPoolExecutor") ThreadPoolTaskExecutor faceRecognitionThreadPoolExecutor) {
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.duplicationService = duplicationService;
        this.photoService = photoService;
        this.faceDetectionService = faceDetectionService;
        this.faceRecognitionService = faceRecognitionService;
        this.immichImportService = immichImportService;
        this.immichAlbumImportService = immichAlbumImportService;
        this.adminService = adminService;
        this.lightThreadPoolExecutor = lightThreadPoolExecutor;
        this.duplicationThreadPoolExecutor = duplicationThreadPoolExecutor;
        this.faceDetectionThreadPoolExecutor = faceDetectionThreadPoolExecutor;
        this.faceRecognitionThreadPoolExecutor = faceRecognitionThreadPoolExecutor;
        // Seeded from the executor's own configured queueCapacity (its ceiling - see
        // adjustLightConcurrencyForCpuLoad) rather than a hardcoded number, so it stays correct if
        // that config value ever changes.
        this.lightConcurrencyLimit = new AtomicInteger(lightThreadPoolExecutor.getQueueCapacity());
    }

    // @Scheduled(fixedDelay=...) guarantees the next tick only starts once this method returns -
    // one poller invocation runs start-to-finish on a single thread, never overlapping itself.
    // Fetched candidates are marked QUEUED (see markQueued/TaskStatus.QUEUED) BEFORE being
    // submitted, not after - a task submitted to an executor doesn't actually start running (and
    // so doesn't flip to PROCESSING via markProcessing) until a worker thread picks it up, which
    // can lag behind submission whenever the executor is backed up. Without marking QUEUED first,
    // that still-PENDING-looking row would match this same query again on the next tick and get
    // submitted a SECOND time before the first submission ever ran - the same task executing
    // multiple times concurrently. Heavy tasks have their own, much less frequent poller - see
    // pollHeavyTasks.
    @Scheduled(fixedDelay = 5000)
    public void pollPendingTasks() {
        adjustLightConcurrencyForCpuLoad();
        int limit = lightConcurrencyLimit.get();

        Set<TaskEntity> lightTasks = new HashSet<>();
        if (lightThreadPoolExecutor.getQueueSize() < limit) {
            lightTasks = transactionTemplate.execute(status ->
                    taskRepository.findExecutableTasks(TaskStatus.PENDING.name(), LocalDateTime.now(), LIGHT_TASK_TYPES, Limit.of(limit - lightThreadPoolExecutor.getQueueSize())));
        }

        markQueued(lightTasks);
        for (TaskEntity task : lightTasks) {
            lightThreadPoolExecutor.submit(() -> processTask(task.getId()));
        }
    }

    // See pollPendingTasks' own comment for why this has to happen before submit(), not after -
    // one transaction for the whole batch (not one per task) since this always runs synchronously,
    // right here, well before any of these tasks could possibly have already started (they haven't
    // even been submitted yet).
    private void markQueued(Set<TaskEntity> tasks) {
        if (tasks.isEmpty()) return;
        transactionTemplate.executeWithoutResult(status -> {
            for (TaskEntity task : tasks) {
                task.setStatus(TaskStatus.QUEUED);
                taskRepository.save(task);
            }
        });
    }

    // lightThreadPoolExecutor runs on virtual threads (see ThreadPoolConfiguration), which makes
    // corePoolSize/maxPoolSize essentially inert for controlling concurrency here - a
    // virtual-thread-per-task executor doesn't pool in the traditional sense, so adjusting those
    // fields (the way adjustHeavyPoolSizeForLightLoad below does for the heavy pool) wouldn't
    // actually change anything. queueCapacity isn't adjustable at runtime either - Spring only
    // reads it once, to size the underlying queue, at executor initialization. So the real lever
    // for "how much light-task concurrency is allowed right now" is how many new tasks
    // pollPendingTasks pulls from the database each tick - lightConcurrencyLimit, stepped up/down
    // here based on overall machine CPU load rather than a fixed number.
    // com.sun.management.OperatingSystemMXBean#getCpuLoad() (0.0-1.0, recent load since the last
    // call) is JDK-internal-looking by package name but has been a stable, standard part of every
    // HotSpot-derived JVM (OpenJDK, Temurin, ...) for years - returns -1 only on its very first
    // call or if genuinely unsupported on this platform, in which case this is a no-op and the
    // limit stays wherever it last was.
    private void adjustLightConcurrencyForCpuLoad() {
        double cpuLoad = OS_BEAN.getCpuLoad();
        if (cpuLoad < 0) return;
        int ceiling = lightThreadPoolExecutor.getQueueCapacity();
        int previous = lightConcurrencyLimit.get();
        int updated;
        if (cpuLoad > CPU_LOAD_HIGH_THRESHOLD) {
            updated = lightConcurrencyLimit.updateAndGet(v -> Math.max(LIGHT_CONCURRENCY_MIN, v - LIGHT_CONCURRENCY_STEP));
        } else if (cpuLoad < CPU_LOAD_LOW_THRESHOLD) {
            updated = lightConcurrencyLimit.updateAndGet(v -> Math.min(ceiling, v + LIGHT_CONCURRENCY_STEP));
        } else {
            return;
        }
        if (updated != previous) {
            log.info("CPU load {} - adjusted light task concurrency limit from {} to {}", String.format("%.2f", cpuLoad), previous, updated);
        }
    }

    // Heavy tasks (CHECK_DUPLICATION/ISOLATE_FACES/FACE_RECOGNITION) are the expensive,
    // resource-intensive half of the pipeline - gated behind an admin on/off switch (see
    // AdminService#isHeavyTasksActivated/ACTIVATE_HEAVY_TASKS) so a struggling machine can pause
    // this half entirely without touching light/control tasks. Runs far less often than the light
    // poller (5 minutes vs 5 seconds) since that's the whole point of isolating it: a
    // resource-constrained machine only wants to consider starting new heavy work occasionally,
    // not every 5 seconds. No row yet, or its most recent payload says activate=false, means
    // heavy tasks stay paused - opt-in by design, not opt-out.
    //
    // Each type is polled and submitted against its OWN dedicated pool (see the fields above) -
    // not one shared pool for all three - so one type's backlog can never crowd out another's
    // thread, and each type's real concurrency ceiling (configured via
    // IPhotoProperties.HeavyTasks) is enforced by the pool itself rather than an internal lock.
    @Scheduled(fixedDelay = 300_000)
    public void pollHeavyTasks() {
        if (!adminService.isHeavyTasksActivated()) return;

        pollHeavyTaskType(TaskType.CHECK_DUPLICATION, duplicationThreadPoolExecutor);
        //pollHeavyTaskType(TaskType.ISOLATE_FACES, faceDetectionThreadPoolExecutor);
        pollHeavyTaskType(TaskType.FACE_RECOGNITION, faceRecognitionThreadPoolExecutor);
    }

    private void pollHeavyTaskType(TaskType taskType, ThreadPoolTaskExecutor executor) {
        Set<TaskEntity> tasks = new HashSet<>();
        if (executor.getQueueSize() < executor.getQueueCapacity()) {
            tasks = transactionTemplate.execute(status ->
                    taskRepository.findExecutableTasks(TaskStatus.PENDING.name(), LocalDateTime.now(), Set.of(taskType), Limit.of(executor.getQueueCapacity() - executor.getQueueSize())));
        }
        markQueued(tasks);
        for (TaskEntity task : tasks) {
            executor.submit(() -> processTask(task.getId()));
        }
    }

    private void markProcessing(UUID taskId) {
        transactionTemplate.executeWithoutResult(status -> taskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus(TaskStatus.PROCESSING);
            taskRepository.save(task);
        }));
    }

    // How long a task can sit in PROCESSING before this sweep considers it orphaned rather than
    // genuinely still running.
    private static final Duration STUCK_TASK_AGE = Duration.ofHours(1);
    // Reclaimed tasks get a randomized retry delay in this range, not all the same fixed offset -
    // spreads them out so a sweep that reclaims many tasks at once doesn't turn them all
    // executable at the exact same instant and re-create the very pile-up that likely orphaned
    // some of them in the first place.
    private static final int MIN_RETRY_DELAY_MINUTES = 1;
    private static final int MAX_RETRY_DELAY_MINUTES = 15;

    // Statuses that mean "in flight" (claimed by a poller or actively running) rather than a
    // terminal/not-yet-claimed state - see reclaimStuckProcessingTasks and IPhotoApplication's
    // startup reset, which both treat these two identically (the only difference between them is
    // WHEN each is orphaned: QUEUED if submit() itself never actually ran, PROCESSING if the
    // worker thread died after starting).
    private static final Set<String> IN_FLIGHT_STATUSES = Set.of(TaskStatus.QUEUED.name(), TaskStatus.PROCESSING.name());

    // General safety net, across every task type: pollPendingTasks above only ever looks at
    // PENDING tasks, so a task left QUEUED (submit() itself failed/never actually entered the
    // executor) or PROCESSING (worker thread died mid-task - app restart, JVM crash, ...) is
    // otherwise never picked up again and sits stuck forever - this is exactly what happened to a
    // real Immich import day (see specification.md/TODO.md), where the fix at the time was scoped
    // to just that one feature's own retry button (ImmichImportService#isReclaimable). This sweep
    // is the general version: runs hourly, independent of any user action, and covers every task
    // type, not just Immich's. Distinct from (and a complement to) IPhotoApplication's startup
    // reset - that one runs once at boot and needs no staleness check at all (a fresh JVM means
    // ANY leftover QUEUED/PROCESSING row is unconditionally orphaned); this one is the ongoing
    // safety net for the app staying up but a task still getting orphaned some other way (e.g. a
    // silently-dying worker thread, or submit() throwing without a restart ever happening), where
    // "stuck for over an hour" is the only signal available to tell that apart from genuinely
    // still running.
    @Scheduled(fixedDelay = 3_600_000)
    public void reclaimStuckProcessingTasks() {
        LocalDateTime threshold = LocalDateTime.now().minus(STUCK_TASK_AGE);
        transactionTemplate.executeWithoutResult(status -> {
            List<TaskEntity> stuck = taskRepository.findByStatusInAndUpdatedAtBefore(IN_FLIGHT_STATUSES, threshold);
            if (stuck.isEmpty()) return;
            log.warn("Reclaiming {} task(s) stuck QUEUED/PROCESSING for over {} - resetting to PENDING with a randomized retry delay",
                    stuck.size(), STUCK_TASK_AGE);
            for (TaskEntity task : stuck) {
                task.setStatus(TaskStatus.PENDING);
                task.setExecuteAt(LocalDateTime.now().plusMinutes(randomRetryDelayMinutes()));
                taskRepository.save(task);
            }
        });
    }

    private int randomRetryDelayMinutes() {
        return MIN_RETRY_DELAY_MINUTES + ThreadLocalRandom.current().nextInt(MAX_RETRY_DELAY_MINUTES - MIN_RETRY_DELAY_MINUTES + 1);
    }

    private void processTask(UUID taskId) {
        markProcessing(taskId);
        transactionTemplate.executeWithoutResult(status -> {
            TaskEntity task = taskRepository.findById(taskId).orElse(null);
            if (task == null) return;
            try {
                if (PROCESS_UPLOAD.equals(task.getTaskType())) {
                    processUpload(task);
                } else if (PERMANENT_DELETE.equals(task.getTaskType())) {
                    handlePermanentDelete(task);
                    task.setStatus(TaskStatus.COMPLETED);
                } else if (HASH_IMAGE.equals(task.getTaskType())) {
                    duplicationService.hashImage(task);
                    task.setStatus(TaskStatus.COMPLETED);
                } else if (CHECK_DUPLICATION.equals(task.getTaskType())) {
                    duplicationService.checkDuplication(task);
                } else if (ISOLATE_FACES.equals(task.getTaskType())) {
                    faceDetectionService.detectAndSaveFaces(task);
                } else if (FACE_RECOGNITION.equals(task.getTaskType())) {
                    faceRecognitionService.groupFaces();
                    // TODO to be updated
                    task.setExecuteAt(LocalDateTime.now().plusSeconds(30));
                    task.setStatus(TaskStatus.PENDING);
                } else if (SYNC_FROM_IMMICH.equals(task.getTaskType())) {
                    // Phase 1: staging only, never touches photos/photo_metadata (see
                    // ImmichImportService). An outcome comes back only when the asset was fully
                    // handled without needing phase 2 (already imported / content-duplicate) -
                    // otherwise phase 2 (UPLOAD_FROM_IMMICH, already queued by this call) is what
                    // records the day row's outcome once it finishes.
                    Optional<ImmichAssetImportOutcome> outcome = immichImportService.processSyncFromImmichTask(task);
                    task.setStatus(TaskStatus.COMPLETED);
                    outcome.ifPresent(o -> immichImportService.recordAssetTaskOutcome(task, o));
                } else if (UPLOAD_FROM_IMMICH.equals(task.getTaskType())) {
                    ImmichAssetImportOutcome outcome = immichImportService.finalizeUploadFromImmich(task);
                    task.setStatus(TaskStatus.COMPLETED);
                    immichImportService.recordAssetTaskOutcome(task, outcome);
                } else if (IMPORT_IMMICH_ALBUM.equals(task.getTaskType())) {
                    immichAlbumImportService.processAlbumImportTask(task);
                    task.setStatus(TaskStatus.COMPLETED);
                } else if (ACTIVATE_HEAVY_TASKS.equals(task.getTaskType())) {
                    // Not real work to execute - this row exists purely so pollHeavyTasks can read
                    // the admin's latest activate/deactivate choice (see
                    // AdminService#isHeavyTasksActivated, which queries for it directly rather than
                    // relying on this task ever actually running). Still picked up by the light
                    // poller like any other light task type though (heavy=false), so it needs a
                    // terminal status here or it would sit stuck in PROCESSING forever.
                    task.setStatus(TaskStatus.COMPLETED);
                }
            }
            catch (NonTransientDataAccessException e) {
                log.warn("Task {} hit a Not transient database error", task.getId(), e);
                task.setStatus(TaskStatus.FAILED);
            }
            catch (DataAccessException e) {
                log.warn("Task {} hit a database error", task.getId(), e);
                task.setStatus(TaskStatus.PENDING);
                task.setExecuteAt(LocalDateTime.now().plusMinutes(randomRetryDelayMinutes()));
            }
            catch (ImmichApiException e) {
                log.warn("Task {} hit a network error talking to Immich", task.getId(), e);
                task.setStatus(TaskStatus.PENDING);
                task.setExecuteAt(LocalDateTime.now().plusMinutes(randomRetryDelayMinutes()));
            }
            catch (Throwable e) {
                log.error("Task failed", e);
                task.setStatus(TaskStatus.FAILED);
                task.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                task.setStackTrace(sw.toString());
                if (SYNC_FROM_IMMICH.equals(task.getTaskType()) || UPLOAD_FROM_IMMICH.equals(task.getTaskType())) {
                    immichImportService.recordAssetTaskFailure(task);
                }
            }
            taskRepository.save(task);
        });
    }

    private void processUpload(TaskEntity task) throws Exception {
        UploadStatus uploadStatus = handleUploadTask(task);
        switch (uploadStatus) {
            case DUPLICATED -> {
                task.setStatus(TaskStatus.IGNORED);
                task.setErrorMessage("Duplicate asset detected");
            }
            case TRASHED -> {
                task.setStatus(TaskStatus.TRASHED);
                task.setErrorMessage("Duplicate asset detected and trashed");
            }
            case PENDING -> {
                task.setStatus(TaskStatus.PENDING);
                task.setErrorMessage("Pending");
            }
            default -> task.setStatus(TaskStatus.COMPLETED);
        }

    }

    private UploadStatus handleUploadTask(TaskEntity task) throws Exception {
        Map<String, String> payload = objectMapper.readValue(task.getPayload(), new TypeReference<>() {
        });
        File tempFile = new File(payload.get("tempFilePath"));

        if (!tempFile.exists()) throw new RuntimeException("Temp file not found");

        return photoService.uploadAsset(task, payload, tempFile);
    }



    // This method is now ONLY for the delayed permanent deletion
    private void handlePermanentDelete(TaskEntity task) throws Exception {
        Map<String, String> payload = objectMapper.readValue(task.getPayload(), new TypeReference<>() {
        });
        UUID photoId = UUID.fromString(payload.get("photoId"));

        // Use PhotoService to perform the actual deletion logic
        // This ensures consistency and proper cleanup
        // Note: PhotoService.permanentDeleteAssets expects a list, so wrap it
        photoService.permanentDeleteAssets(List.of(photoId));
        log.info("Asset {} permanently removed by scheduled task.", photoId);
    }


    @PreDestroy
    public void shutdown() {
        // Light tasks run on individually-named, unmanaged virtual threads (see pollPendingTasks)
        // rather than a shared executor, so lightThreadPoolExecutor itself has nothing in flight
        // to wait on here - shut down anyway for consistency/symmetry with the heavy pools.
        lightThreadPoolExecutor.shutdown();
        duplicationThreadPoolExecutor.shutdown();
        faceDetectionThreadPoolExecutor.shutdown();
        faceRecognitionThreadPoolExecutor.shutdown();
    }
}
