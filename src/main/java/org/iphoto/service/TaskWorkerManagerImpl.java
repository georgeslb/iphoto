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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.iphoto.model.TaskType.*;

@Service
@Slf4j
public class TaskWorkerManagerImpl implements TaskWorkerManager {

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
    private final ThreadPoolTaskExecutor duplicationThreadPoolExecutor;
    private final ThreadPoolTaskExecutor middleThreadPoolExecutor;
    private final ThreadPoolTaskExecutor faceRecognitionThreadPoolExecutor;

    private static final Set<TaskType> LIGHT_TASK_TYPES =
            Stream.of(TaskType.values()).filter(taskType -> taskType.getTaskTypeCharge() == TaskTypeCharge.LIGHT).collect(Collectors.toSet());

    private static final Set<TaskType> MIDDLE_TASK_TYPES =
            Stream.of(TaskType.values()).filter(taskType -> taskType.getTaskTypeCharge() == TaskTypeCharge.MIDDLE).collect(Collectors.toSet());

    // See adjustLightConcurrencyForCpuLoad below for why this (not corePoolSize/maxPoolSize) is
    // the actual concurrency knob for the light pool.
    private static final OperatingSystemMXBean OS_BEAN =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final int LIGHT_CONCURRENCY_MIN = 10;
    private static final int LIGHT_CONCURRENCY_STEP = 10;
    private static final double CPU_LOAD_LOW_THRESHOLD = 0.5;
    private static final double CPU_LOAD_HIGH_THRESHOLD = 0.85;
    private final AtomicInteger lightConcurrencyLimit;

    // In-memory guard against submitting the same task id to an executor twice while it's already
    // in flight (queued or actively PROCESSING) - a real race, not theoretical: reclaimStuckProcessingTasks
    // (see below) resets any task still QUEUED/PROCESSING past STUCK_TASK_AGE back to PENDING,
    // which the next poll*PendingTasks tick would otherwise happily pick up and submit AGAIN even
    // though the original run is still genuinely executing (a large Immich video download/upload
    // taking longer than expected, not actually stuck). Two concurrent executions of the exact
    // same UPLOAD_FROM_IMMICH task is exactly what produced a real production
    // java.nio.file.FileAlreadyExistsException on Files.move (see
    // ImmichImportServiceImpl#finalizeUploadFromImmich) - one execution wins and completes
    // normally, the other trips over the file the winner already placed and gets marked FAILED,
    // permanently skewing the day's imported/failed counters (see
    // ImmichImportServiceImpl#reconcileOrphanedCompletedAsset, which now also corrects that
    // specific double-count once a retry notices the asset is actually fully imported after all).
    // A task id is added here the instant a poller decides to submit it (before executor.submit,
    // so there's no window where it's "queued" but not yet tracked) and removed only once
    // processTask has fully finished with it either way - see processTask's own comment.
    // ConcurrentHashMap.newKeySet() (NOT a CopyOnWriteArrayList, which this used to be) specifically
    // so every poll*PendingTasks/reclaimStuckProcessingTasks call site below can do the
    // check-and-mark-in-flight as ONE atomic Set.add() call: a separate "if (!contains) add(...)"
    // pair is a classic check-then-act race between any two of those methods running on different
    // threads at once - e.g. this scheduled poller's own timer thread ("scheduling-1") racing a
    // caller that also triggers a poll synchronously (AbstractIntegrationTest.waitForTaskCompletion
    // does exactly this in the test suite) - both can see "not yet queued" before either has added
    // it, and both submit the SAME task id, reproducing this exact double-execution hazard for
    // whichever task the race happens to land on, not just UPLOAD_FROM_IMMICH.
    private final Set<UUID> queuedTasks = ConcurrentHashMap.newKeySet();

    public TaskWorkerManagerImpl(TaskRepository taskRepository, ObjectMapper objectMapper, TransactionTemplate transactionTemplate, DuplicationService duplicationService, PhotoService photoService, FaceDetectionService faceDetectionService, FaceRecognitionService faceRecognitionService
            , ImmichImportService immichImportService, ImmichAlbumImportService immichAlbumImportService, AdminService adminService, @Qualifier("lightThreadPoolExecutor") ThreadPoolTaskExecutor lightThreadPoolExecutor,
              @Qualifier("duplicationThreadPoolExecutor") ThreadPoolTaskExecutor duplicationThreadPoolExecutor,
              @Qualifier("middleThreadPoolExecutor") ThreadPoolTaskExecutor middleThreadPoolExecutor,
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
        this.middleThreadPoolExecutor = middleThreadPoolExecutor;
        this.faceRecognitionThreadPoolExecutor = faceRecognitionThreadPoolExecutor;
        // Seeded from the executor's own configured queueCapacity (its ceiling - see
        // adjustLightConcurrencyForCpuLoad) rather than a hardcoded number, so it stays correct if
        // that config value ever changes.
        this.lightConcurrencyLimit = new AtomicInteger(lightThreadPoolExecutor.getQueueCapacity());
    }

    @Override
    @Scheduled(fixedDelay = 5000)
    public void pollLightPendingTasks() {
        log.info("pollLightPendingTasks starting");
        try {
            adjustLightConcurrencyForCpuLoad();
            int limit = lightConcurrencyLimit.get();

            Set<TaskEntity> lightTasks = new HashSet<>();
            if (lightThreadPoolExecutor.getQueueSize() < limit) {
                lightTasks = transactionTemplate.execute(status ->
                        taskRepository.findExecutableTasks(TaskStatus.PENDING.name(), LocalDateTime.now(), LIGHT_TASK_TYPES, Limit.of(limit - lightThreadPoolExecutor.getQueueSize())));
            }

            markQueued(lightTasks);
            // See queuedTasks' own field comment for why this check-and-add must be one atomic
            // Set.add() call - findExecutableTasks above filters on PENDING, but a task reclaimed by
            // reclaimStuckProcessingTasks while still genuinely running (or this same method racing
            // itself across threads - see the field comment) could otherwise be re-submitted here on
            // top of itself.
            for (TaskEntity task : lightTasks) {
                if (queuedTasks.add(task.getId())) {
                    lightThreadPoolExecutor.submit(() -> processTask(task.getId()));
                }
            }
        } finally {
            log.info("pollLightPendingTasks finished");
        }
    }

    @Scheduled(fixedDelay = 60000)
    @Override
    public void pollMiddleChargeTasks() {
        log.info("pollMiddleChargeTasks starting");
        try {
            Set<TaskEntity> middleTasks = new HashSet<>();
            if (middleThreadPoolExecutor.getQueueSize() < middleThreadPoolExecutor.getQueueCapacity()) {
                middleTasks = transactionTemplate.execute(status ->
                        taskRepository.findExecutableTasks(TaskStatus.PENDING.name(), LocalDateTime.now(), MIDDLE_TASK_TYPES, Limit.of(middleThreadPoolExecutor.getQueueCapacity() - middleThreadPoolExecutor.getQueueSize())));
            }

            markQueued(middleTasks);
            for (TaskEntity task : middleTasks) {
                if (queuedTasks.add(task.getId())) {
                    middleThreadPoolExecutor.submit(() -> processTask(task.getId()));
                }
            }
        } finally {
            log.info("pollMiddleChargeTasks finished");
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

    // Each type is polled and submitted against its OWN dedicated pool (see the fields above) -
    // not one shared pool for all three - so one type's backlog can never crowd out another's
    // thread, and each type's real concurrency ceiling (configured via
    // IPhotoProperties.HeavyTasks) is enforced by the pool itself rather than an internal lock.
    @Override
    @Scheduled(fixedDelay = 300_000)
    public void pollFaceRecognitionTask() {
        log.info("pollFaceRecognitionTask starting");
        try {
            if (!adminService.isHeavyTasksActivated()) return;

            pollHeavyTaskType(TaskType.FACE_RECOGNITION, faceRecognitionThreadPoolExecutor);
        } finally {
            log.info("pollFaceRecognitionTask finished");
        }
    }

    @Scheduled(fixedDelay = 30000)
    @Override
    public void pollDuplicationTask() {
        log.info("pollDuplicationTask starting");
        try {
            if (!adminService.isHeavyTasksActivated()) return;

            pollHeavyTaskType(TaskType.CHECK_DUPLICATION, duplicationThreadPoolExecutor);
        } finally {
            log.info("pollDuplicationTask finished");
        }
    }



    private void pollHeavyTaskType(TaskType taskType, ThreadPoolTaskExecutor executor) {
        Set<TaskEntity> tasks = new HashSet<>();
        if (executor.getQueueSize() < executor.getQueueCapacity()) {
            tasks = transactionTemplate.execute(status ->
                    taskRepository.findExecutableTasks(TaskStatus.PENDING.name(), LocalDateTime.now(), Set.of(taskType), Limit.of(executor.getQueueCapacity() - executor.getQueueSize())));
        }
        markQueued(tasks);
        for (TaskEntity task : tasks) {
            if (queuedTasks.add(task.getId())) {
                executor.submit(() -> processTask(task.getId()));
            }
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

    @Override
    @Scheduled(fixedDelay = 3_600_000)
    public void reclaimStuckProcessingTasks() {
        log.info("reclaimStuckProcessingTasks starting");
        try {
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
        } finally {
            log.info("reclaimStuckProcessingTasks finished");
        }
    }

    // How often logThreadPoolStatus reports. Independent of every poll*PendingTasks interval
    // above (5s-5min) - this is a monitoring tick, not a work tick, so one interval covering all
    // four pools is simpler than matching each pool's own cadence.
    @Override
    @Scheduled(fixedDelay = 60_000)
    public void logThreadPoolStatus() {
        log.info("Thread pool status - light: {} queued/{} active, middle: {} queued/{} active, " +
                        "duplication: {} queued/{} active, faceRecognition: {} queued/{} active, in-flight tasks tracked: {}",
                lightThreadPoolExecutor.getQueueSize(), lightThreadPoolExecutor.getActiveCount(),
                middleThreadPoolExecutor.getQueueSize(), middleThreadPoolExecutor.getActiveCount(),
                duplicationThreadPoolExecutor.getQueueSize(), duplicationThreadPoolExecutor.getActiveCount(),
                faceRecognitionThreadPoolExecutor.getQueueSize(), faceRecognitionThreadPoolExecutor.getActiveCount(),
                queuedTasks.size());
    }

    private int randomRetryDelayMinutes() {
        return MIN_RETRY_DELAY_MINUTES + ThreadLocalRandom.current().nextInt(MAX_RETRY_DELAY_MINUTES - MIN_RETRY_DELAY_MINUTES + 1);
    }

    private void processTask(UUID taskId) {
        markProcessing(taskId);
        // queuedTasks.remove wraps the WHOLE call (not just the inner try/catch below) so it
        // fires even when task == null (row deleted out from under an already-queued/PROCESSING
        // task - e.g. triggerDayImportInternal's own cleanup, see ImmichImportServiceImpl) or if
        // taskRepository.save(task) itself throws - either way this taskId must stop being
        // considered "in flight", or a genuinely later, legitimate re-submission of a *different*
        // task could never happen to reuse this exact (random) UUID anyway, but leaving it in the
        // list forever is still a real bug worth not having, not just a harmless one.
        try {
            transactionTemplate.executeWithoutResult(status -> {
                TaskEntity task = taskRepository.findById(taskId).orElse(null);
                if (task == null) return;
                // A few task types (SYNC_FROM_IMMICH/UPLOAD_FROM_IMMICH - see ImmichImportServiceImpl)
                // already log their own, more detailed "starting"/"done" pair - this one is
                // deliberately generic and uniform across every task type, including the ones that
                // don't have their own, and is the only place that logs the FINAL status explicitly.
                log.info("Task {} ({}) started", task.getId(), task.getTaskType());
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
                log.info("Task {} ({}) finished with status {}", task.getId(), task.getTaskType(), task.getStatus());
                taskRepository.save(task);
            });
        } finally {
            queuedTasks.remove(taskId);
        }
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


    @Override
    @PreDestroy
    public void shutdown() {
        // Light tasks run on individually-named, unmanaged virtual threads (see pollPendingTasks)
        // rather than a shared executor, so lightThreadPoolExecutor itself has nothing in flight
        // to wait on here - shut down anyway for consistency/symmetry with the heavy pools.
        lightThreadPoolExecutor.shutdown();
        duplicationThreadPoolExecutor.shutdown();
        middleThreadPoolExecutor.shutdown();
        faceRecognitionThreadPoolExecutor.shutdown();
    }
}
