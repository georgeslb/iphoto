package org.iphoto.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.TaskEntity;
import org.iphoto.model.TaskType;
import org.iphoto.properties.IPhotoProperties;
import org.iphoto.service.TaskManagerService;
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
import java.util.UUID;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/upload")
@Slf4j
@RequiredArgsConstructor
public class UploadController {

    private final TaskManagerService taskManagerService;
    private final ObjectMapper objectMapper;
    private final IPhotoProperties iPhotoProperties;


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

        Path chunkDir = Paths.get(iPhotoProperties.getStorage().getChunks(), uploadId);
        if (!Files.exists(chunkDir)) {
            Files.createDirectories(chunkDir);
        }



        Path chunkFilePath = chunkDir.resolve(fileName + ".chunk" + chunkIndex);
        chunk.transferTo(chunkFilePath);

        // Check if all chunks have been received
        if (chunkIndex == totalChunks - 1) { // This is the last chunk
            log.info("All chunks received for uploadId: {}. Reassembling file.", uploadId);
            Path finalTempFilePath = Paths.get(iPhotoProperties.getStorage().getTemp(), uploadId + "_" + fileName);
            
            try (var outputStream = Files.newOutputStream(finalTempFilePath)) {
                for (int i = 0; i < totalChunks; i++) {
                    Path currentChunkPath = chunkDir.resolve(fileName + ".chunk" + i);
                    Files.copy(currentChunkPath, outputStream);
                }
            }
            
            // Clean up chunk directory
            try (Stream<Path> walk = Files.walk(chunkDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                Files.deleteIfExists(chunkDir);
            }

            // Create task for processing the reassembled file
            Map<String, String> payload = new HashMap<>();
            payload.put("tempFilePath", finalTempFilePath.toAbsolutePath().toString());
            payload.put("originalFileName", fileName);
            payload.put("contentType", contentType);
            payload.put("fileSize", String.valueOf(fileSize));

            // generate photo UUID and add a new task
            TaskEntity task = taskManagerService
                    .createTaskEntity(TaskType.PROCESS_UPLOAD, UUID.randomUUID(), objectMapper.writeValueAsString(payload), null);



            log.info("Reassembled file {} and created task {}.", fileName, task.getId());
            return Map.of("status", "completed", "taskId", task.getId().toString());
        }

        return Map.of("status", "AWAITING_CHUNKS", "chunkIndex", String.valueOf(chunkIndex));
    }
}
