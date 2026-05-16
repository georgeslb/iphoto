package org.iphoto.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.TaskEntity;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.UploadStatus;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.TaskRepository;
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
    private final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());



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
                executorService.submit(() -> processTask(taskId));
            }
        }
    }

    private void processTask(UUID taskId) {
        transactionTemplate.executeWithoutResult(status -> {
            TaskEntity task = taskRepository.findById(taskId).orElse(null);
            if (task == null) return;
            try {
                if (PROCESS_UPLOAD.name().equals(task.getTaskType())) {
                    processUpload(task);
                } else if (PERMANENT_DELETE.name().equals(task.getTaskType())) {
                    handlePermanentDelete(task);
                    task.setStatus(TaskStatus.COMPLETED);
                } else if (HASH_IMAGE.name().equals(task.getTaskType())) {
                    duplicationService.hashImage(task);
                    task.setStatus(TaskStatus.COMPLETED);
                }
                else if (CHECK_DUPLICATION.name().equals(task.getTaskType())) {
                    duplicationService.checkDuplication(task);
                }
            } catch (Exception e) {
                log.error("Task failed", e);
                task.setStatus(TaskStatus.FAILED);
                task.setErrorMessage(e.getMessage());
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                task.setStackTrace(sw.toString());
            }
            taskRepository.save(task);
        });
    }

    private void processUpload(TaskEntity task) throws Exception {
        switch (handleUploadTask(task)) {
            case DUPLICATED -> {
                task.setStatus(TaskStatus.IGNORED);
                task.setErrorMessage("Duplicate asset detected");
            }
            case TRASHED -> {
                task.setStatus(TaskStatus.TRASHED);
                task.setErrorMessage("Duplicate asset detected and trashed");
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
            } catch (IOException e) {
                throw new RuntimeException(e);
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
