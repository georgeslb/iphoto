package org.iphoto.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.TaskEntity;
import org.iphoto.exception.PhotoUploadException;
import org.iphoto.exception.ServiceNotAvailableException;
import org.iphoto.exception.SpaceLockingException;
import org.iphoto.model.ImmichAssetImportOutcome;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.UploadStatus;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.TaskRepository;
import org.iphoto.service.immich.ImmichImportService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.iphoto.model.TaskType.*;
import static org.iphoto.model.UploadStatus.DUPLICATED;
import static org.iphoto.model.UploadStatus.TRASHED;

@Service
@AllArgsConstructor
@Slf4j
public class TaskWorkerManager {

    private final TaskRepository taskRepository;
    private final PhotoRepository photoRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final DuplicationService duplicationService;
    private final PhotoUploadService photoUploadService;
    private final PhotoService photoService;
    private final FaceDetectionService faceDetectionService;
    private final FaceRecognitionService faceRecognitionService;
    private final ImmichImportService immichImportService;

    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();


    @Scheduled(fixedDelay = 5000)
    public void pollPendingTasks() {
        List<UUID> taskIdsToProcess = transactionTemplate.execute(status -> taskRepository.findExecutableTasks(TaskStatus.PENDING.name(), LocalDateTime.now())
                .stream().map(task -> {
                    task.setStatus(TaskStatus.PROCESSING);
                    taskRepository.save(task);
                    return task.getId();
                }).toList());

        if (taskIdsToProcess != null) {
            for (UUID taskId : taskIdsToProcess) {
                executorService.submit(() -> {
                    Thread virtualThread = Thread.ofVirtual()
                            .name("backend-task" + taskId)
                            .start(() ->  processTask(taskId));
                    virtualThread.start();

                });
            }
        }
    }

    private void processTask(UUID taskId) {
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
            catch (ServiceNotAvailableException e) {
                task.setStatus(TaskStatus.PENDING);
                task.setExecuteAt(null);
            }
            // Multiple UPLOAD_FROM_IMMICH tasks for assets in the same space finish in parallel
            // on different virtual threads, and SpaceService.lockSpace uses a non-blocking
            // tryLock - one of them losing that race throws this rather than waiting. Treat it the
            // same as ServiceNotAvailableException (immediate same-process retry on the next
            // 5-second poll) rather than a real failure - the lock is normally held only briefly.
            catch (SpaceLockingException e) {
                task.setStatus(TaskStatus.PENDING);
                task.setExecuteAt(null);
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

        String hash;
        try (FileInputStream fis = new FileInputStream(tempFile)) {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(fis.readAllBytes()));
        }

        return photoRepository.findByFileHash(hash).map(photoEntity -> {
            try {
                Files.deleteIfExists(tempFile.toPath());
                task.setPhotoId(photoEntity.getId());
                if (photoEntity.getIsGarbage()) {
                    return TRASHED;
                }
                return DUPLICATED;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        }).orElseGet(() -> {
            try {
                return photoUploadService.processNewAsset(task, payload, tempFile, hash);
            } catch (Exception e) {
                throw new PhotoUploadException(e);
            }
        });
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
        executorService.shutdown();
    }
}
