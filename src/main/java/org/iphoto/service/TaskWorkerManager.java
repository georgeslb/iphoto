package org.iphoto.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.TaskEntity;
import org.iphoto.exception.ImmichApiException;
import org.iphoto.exception.ServiceNotAvailableException;
import org.iphoto.exception.SpaceLockingException;
import org.iphoto.model.ImmichAssetImportOutcome;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;
import org.iphoto.model.UploadStatus;
import org.iphoto.repository.TaskRepository;
import org.iphoto.service.immich.ImmichImportService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
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
    private final ThreadPoolTaskExecutor lightThreadPoolExecutor;
    private final ThreadPoolTaskExecutor heavyThreadPoolExecutor;

    private static final Set<TaskType> HEAVY_TASK_TYPES =
            Stream.of(TaskType.values()).filter(TaskType::isHeavy).collect(Collectors.toSet());
    private static final Set<TaskType> LIGHT_TASK_TYPES =
            Stream.of(TaskType.values()).filter(taskType -> !taskType.isHeavy()).collect(Collectors.toSet());

    public TaskWorkerManager(TaskRepository taskRepository, ObjectMapper objectMapper, TransactionTemplate transactionTemplate, DuplicationService duplicationService, PhotoService photoService, FaceDetectionService faceDetectionService, FaceRecognitionService faceRecognitionService
            , ImmichImportService immichImportService, @Qualifier("lightThreadPoolExecutor") ThreadPoolTaskExecutor lightThreadPoolExecutor, @Qualifier("heavyThreadPoolExecutor") ThreadPoolTaskExecutor heavyThreadPoolExecutor) {
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.duplicationService = duplicationService;
        this.photoService = photoService;
        this.faceDetectionService = faceDetectionService;
        this.faceRecognitionService = faceRecognitionService;
        this.immichImportService = immichImportService;
        this.lightThreadPoolExecutor = lightThreadPoolExecutor;
        this.heavyThreadPoolExecutor = heavyThreadPoolExecutor;
    }

    // @Scheduled(fixedDelay=...) guarantees the next tick only starts once this method returns -
    // one poller invocation runs start-to-finish on a single thread, never overlapping itself - so
    // fetched candidates only need flipping to PROCESSING one at a time, right before each is
    // actually dispatched, rather than the whole batch upfront in one transaction. A task not yet
    // reached this iteration - or deliberately skipped, see the heavy-task branch below - simply
    // stays PENDING and is reconsidered on this same pass or the next one 5 seconds later; there's
    // no window where a task can be marked PROCESSING without also being dispatched in the same
    // step, so no separate "revert back to PENDING" undo path is needed either.
    @Scheduled(fixedDelay = 5000)
    public void pollPendingTasks() {
        Set<TaskEntity> lightTasks = new HashSet<>();
        if (lightThreadPoolExecutor.getQueueSize() < lightThreadPoolExecutor.getQueueCapacity()) {
            lightTasks = transactionTemplate.execute(status ->
                    taskRepository.findExecutableTasks(TaskStatus.PENDING.name(), LocalDateTime.now(), LIGHT_TASK_TYPES, Limit.of(lightThreadPoolExecutor.getQueueCapacity() - lightThreadPoolExecutor.getQueueSize())));
        }
        Set<TaskEntity> heavyTasks = new HashSet<>();
        if (heavyThreadPoolExecutor.getQueueSize() < heavyThreadPoolExecutor.getQueueCapacity()) {
            heavyTasks = transactionTemplate.execute(status ->
                    taskRepository.findExecutableTasks(TaskStatus.PENDING.name(), LocalDateTime.now(), HEAVY_TASK_TYPES, Limit.of(heavyThreadPoolExecutor.getQueueCapacity() - heavyThreadPoolExecutor.getQueueSize())));
        }

        if (lightThreadPoolExecutor.getQueueSize() < lightThreadPoolExecutor.getQueueCapacity()  / 2 && heavyThreadPoolExecutor.getMaxPoolSize() != 8 ) {
            log.warn("adjusting heavyThreadPoolExecutor MaxPoolSize to 8");
            heavyThreadPoolExecutor.setMaxPoolSize(8);
        }
        if (lightThreadPoolExecutor.getQueueSize() > lightThreadPoolExecutor.getQueueCapacity()  - 10 && heavyThreadPoolExecutor.getMaxPoolSize() != 3 ) {
            log.warn("adjusting heavyThreadPoolExecutor MaxPoolSize to 3");
            heavyThreadPoolExecutor.setMaxPoolSize(3);
        }
        for (TaskEntity task : heavyTasks) {
            heavyThreadPoolExecutor.submit(() -> processTask(task.getId()));
        }
        for (TaskEntity task : lightTasks) {
            lightThreadPoolExecutor.submit(() -> processTask(task.getId()));
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

    // General safety net, across every task type: pollPendingTasks above only ever looks at
    // PENDING tasks, so a task left in PROCESSING when its worker thread dies mid-task (app
    // restart, JVM crash, ...) is otherwise never picked up again and sits stuck forever - this is
    // exactly what happened to a real Immich import day (see specification.md/TODO.md), where the
    // fix at the time was scoped to just that one feature's own retry button
    // (ImmichImportService#isReclaimable). This sweep is the general version: runs hourly,
    // independent of any user action, and covers every task type, not just Immich's.
    @Scheduled(fixedDelay = 3_600_000)
    public void reclaimStuckProcessingTasks() {
        LocalDateTime threshold = LocalDateTime.now().minus(STUCK_TASK_AGE);
        transactionTemplate.executeWithoutResult(status -> {
            List<TaskEntity> stuck = taskRepository.findByStatusAndUpdatedAtBefore(TaskStatus.PROCESSING.name(), threshold);
            if (stuck.isEmpty()) return;
            log.warn("Reclaiming {} task(s) stuck in PROCESSING for over {} - resetting to PENDING with a randomized retry delay",
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
                }
            }
            catch (ServiceNotAvailableException | SpaceLockingException e) {
                task.setStatus(TaskStatus.PENDING);
                task.setExecuteAt(LocalDateTime.now().plusMinutes(randomRetryDelayMinutes()));
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
        // Only heavyTaskExecutor is an actual ExecutorService now - light tasks run on
        // individually-named, unmanaged virtual threads (see pollPendingTasks) rather than a
        // shared executor, so there's nothing else here to shut down.
        heavyThreadPoolExecutor.shutdown();
        lightThreadPoolExecutor.shutdown();
    }
}
