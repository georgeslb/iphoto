package org.iphoto.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.TaskEntity;
import org.iphoto.entities.UserEntity; // Import UserEntity
import org.iphoto.model.TaskDTO;
import org.iphoto.model.TaskType;
import org.iphoto.model.UploadChunkResponse;
import org.iphoto.properties.IPhotoProperties;
import org.iphoto.repository.UserRepository; // Import UserRepository
import org.iphoto.service.SpaceService;
import org.iphoto.service.TaskManagerService;
import org.iphoto.service.TaskService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
import java.util.NoSuchElementException;
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
    private final UserRepository userRepository; // Inject UserRepository
    private final TaskService taskService;
    private final SpaceService spaceService;

    private UserEntity getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + username));
    }

    // Endpoint to receive individual file chunks
    @PostMapping(value = "/chunk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadChunkResponse> uploadChunk(
            @RequestParam("uploadId") String uploadId,
            @RequestParam("fileName") String fileName,
            @RequestParam("fileSize") long fileSize,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("totalChunks") int totalChunks,
            @RequestParam("contentType") String contentType,
            @RequestParam(value = "albumId", required = false) UUID albumId,
            @RequestParam(value = "spaceId", required = false) UUID spaceId,
            @RequestParam("chunk") MultipartFile chunk) throws IOException {

        UserEntity currentUser = getCurrentUser(); // Get the current user

        // Block/unblock-on-sync: reject before writing any chunk to disk, not just at final
        // reassembly - a locked space's upload is fully rejected, not "accepted then discarded".
        // No spaceId means this lands in the caller's MAIN space, which can never be sync-locked
        // (see SpaceService#lockForSync), so there's nothing to check in that case.
        if (spaceId != null) {
            spaceService.getSpaceById(spaceId)
                    .filter(space -> space.getUser().getId().equals(currentUser.getId()))
                    .ifPresent(spaceService::assertSyncWritable);
        }

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
            Path finalTempFilePath = Paths.get(iPhotoProperties.getStorage().getTemp(), uploadId);

            if (!Files.exists(Paths.get(iPhotoProperties.getStorage().getTemp()))) {
                Files.createDirectories(Paths.get(iPhotoProperties.getStorage().getTemp()));
            }

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
            if (albumId != null) {
                payload.put("albumId", albumId.toString());
            }
            if (spaceId != null) {
                payload.put("spaceId", spaceId.toString());
            }
            payload.put("userId", currentUser.getId().toString()); // Add user ID to payload

            // generate photo UUID and add a new task
            TaskEntity taskEntity = taskManagerService
                    .createTaskEntity(currentUser, TaskType.PROCESS_UPLOAD, UUID.randomUUID(), objectMapper.writeValueAsString(payload), null);
            TaskDTO taskDTO = new TaskDTO(taskEntity.getId(), taskEntity.getStatus().toString(), taskEntity.getPhotoId());
            log.info("Reassembled file {} and created task {}.", fileName, taskEntity.getId());
            return ResponseEntity.ok(new UploadChunkResponse("completed", null, taskDTO));

        }
        return ResponseEntity.ok(new UploadChunkResponse("AWAITING_CHUNKS", String.valueOf(chunkIndex), null));
    }
}