package org.iphoto.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.iphoto.model.TaskEntity;
import org.iphoto.model.UploadResponse;
import org.iphoto.repository.TaskRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/upload")
public class UploadController {

    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    @Value("${iphoto.storage.temp}")
    private String tempStoragePath;

    public UploadController(TaskRepository taskRepository, ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse uploadFile(@RequestParam("file") MultipartFile file) throws IOException {
        Path tempPath = Paths.get(tempStoragePath);
        if (!Files.exists(tempPath)) {
            Files.createDirectories(tempPath);
        }

        String tempFileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        File tempFile = tempPath.resolve(tempFileName).toFile();
        file.transferTo(tempFile);

        Map<String, String> payload = new HashMap<>();
        payload.put("tempFilePath", tempFile.getAbsolutePath());
        payload.put("originalFileName", file.getOriginalFilename());
        payload.put("contentType", file.getContentType());

        TaskEntity task = new TaskEntity();
        task.setTaskType("PROCESS_UPLOAD");
        task.setPayload(objectMapper.writeValueAsString(payload));
        
        taskRepository.save(task);

        return new UploadResponse("Upload received, processing started", task.getId());
    }
}
