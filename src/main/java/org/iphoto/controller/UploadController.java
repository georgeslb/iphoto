package org.iphoto.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.iphoto.model.TaskEntity;
import org.iphoto.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/upload")
public class UploadController {
    private static final Logger log = LoggerFactory.getLogger(UploadController.class);

    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    @Value("${iphoto.storage.temp}")
    private String tempStoragePath;
    
    @Value("${iphoto.storage.chunks}") // New property for chunk storage
    private String chunkStoragePath;

    public UploadController(TaskRepository taskRepository, ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
    }

    // Endpoint to receive individual file chunks
    @PostMapping(value = "/chunk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> uploadChunk(
            @RequestParam("uploadId") String uploadId,
            @RequestParam("fileName") String fileName,
            @RequestParam("fileSize") long fileSize,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("totalChunks") int totalChunks,
            @RequestParam("contentType") String contentType,
            @RequestParam("chunk") MultipartFile chunk) throws IOException {

        log.info("Received chunk {}/{} for uploadId: {} - fileName: {}", chunkIndex, totalChunks, uploadId, fileName);

        Path chunkDir = Paths.get(chunkStoragePath, uploadId);
        if (!Files.exists(chunkDir)) {
            Files.createDirectories(chunkDir);
        }

        Path chunkFilePath = chunkDir.resolve(fileName + ".chunk" + chunkIndex);
        chunk.transferTo(chunkFilePath);

        // Check if all chunks have been received
        if (chunkIndex == totalChunks - 1) { // This is the last chunk
            log.info("All chunks received for uploadId: {}. Reassembling file.", uploadId);
            Path finalTempFilePath = Paths.get(tempStoragePath, uploadId + "_" + fileName);
            
            try (var outputStream = Files.newOutputStream(finalTempFilePath)) {
                for (int i = 0; i < totalChunks; i++) {
                    Path currentChunkPath = chunkDir.resolve(fileName + ".chunk" + i);
                    Files.copy(currentChunkPath, outputStream);
                }
            }
            
            // Clean up chunk directory
            Files.walk(chunkDir)
                 .sorted(java.util.Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(File::delete);
            Files.deleteIfExists(chunkDir);

            // Create task for processing the reassembled file
            Map<String, String> payload = new HashMap<>();
            payload.put("tempFilePath", finalTempFilePath.toAbsolutePath().toString());
            payload.put("originalFileName", fileName);
            payload.put("contentType", contentType);
            payload.put("fileSize", String.valueOf(fileSize)); // Pass original file size

            TaskEntity task = new TaskEntity();
            task.setTaskType("PROCESS_UPLOAD");
            task.setPayload(objectMapper.writeValueAsString(payload));
            taskRepository.save(task);

            log.info("Reassembled file {} and created task {}.", fileName, task.getId());
            return Map.of("status", "completed", "taskId", task.getId().toString());
        }

        return Map.of("status", "AWAITING_CHUNKS", "chunkIndex", String.valueOf(chunkIndex));
    }
}
