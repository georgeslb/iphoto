package org.iphoto.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iphoto.model.Photo;
import org.iphoto.model.PhotoEntity;
import org.iphoto.model.TaskEntity;
import org.iphoto.model.TaskStatus;
import org.iphoto.repository.PhotoMetadataRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.TaskRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PhotoService {

    private final PhotoRepository photoRepository;
    private final PhotoMetadataRepository metadataRepository;
    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    public PhotoService(PhotoRepository photoRepository, PhotoMetadataRepository metadataRepository, TaskRepository taskRepository, ObjectMapper objectMapper) {
        this.photoRepository = photoRepository;
        this.metadataRepository = metadataRepository;
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
    }

    public Page<Photo> getPhotos(Pageable pageable) {
        return photoRepository.findByIsGarbageFalse(pageable).map(this::convertToDto);
    }

    public Page<Photo> getGarbagePhotos(Pageable pageable) {
        return photoRepository.findByIsGarbageTrue(pageable).map(this::convertToDto);
    }

    @Transactional
    public void restoreAssetsFromGarbage(List<UUID> ids) {
        for (UUID id : ids) {
            PhotoEntity entity = photoRepository.findById(id).orElse(null);
            if (entity == null || !entity.getIsGarbage()) continue;

            entity.setIsGarbage(false);
            entity.setGarbageAt(null);
            photoRepository.save(entity);

            List<TaskEntity> pendingDeletes = taskRepository.findExecutableTasks(TaskStatus.PENDING.name(), LocalDateTime.now().plusYears(1));
            for (TaskEntity task : pendingDeletes) {
                try {
                    Map<String, String> payload = objectMapper.readValue(task.getPayload(), new TypeReference<>() {});
                    if ("PERMANENT_DELETE".equals(task.getTaskType()) && id.toString().equals(payload.get("photoId"))) {
                        task.setStatus(TaskStatus.IGNORED);
                        task.setErrorMessage("Asset was restored by user, permanent delete task cancelled.");
                        taskRepository.save(task);
                    }
                } catch (IOException e) {
                    System.err.println("Error parsing task payload for task " + task.getId() + ": " + e.getMessage());
                }
            }
        }
    }

    @Transactional
    public void moveAssetsToGarbage(List<UUID> ids) throws Exception {
        for (UUID id : ids) {
            PhotoEntity entity = photoRepository.findById(id).orElse(null);
            if (entity == null || entity.getIsGarbage()) continue;

            entity.setIsGarbage(true);
            entity.setGarbageAt(LocalDateTime.now());
            photoRepository.save(entity);

            Map<String, String> payload = Map.of("photoId", id.toString());
            TaskEntity deleteTask = new TaskEntity();
            deleteTask.setTaskType("PERMANENT_DELETE");
            deleteTask.setPayload(objectMapper.writeValueAsString(payload));
            deleteTask.setExecuteAt(LocalDateTime.now().plusDays(30));
            taskRepository.save(deleteTask);
        }
    }

    @Transactional
    public void permanentDeleteAssets(List<UUID> ids) throws IOException {
        for (UUID id : ids) {
            PhotoEntity entity = photoRepository.findById(id).orElse(null);
            if (entity == null) continue;

            Files.deleteIfExists(Paths.get(entity.getOriginalPath()));
            if (entity.getThumbnailPath() != null) {
                Files.deleteIfExists(Paths.get(entity.getThumbnailPath()));
            }

            metadataRepository.deleteById(id);
            photoRepository.delete(entity);

            List<TaskEntity> pendingDeletes = taskRepository.findExecutableTasks(TaskStatus.PENDING.name(), LocalDateTime.now().plusYears(1));
            for (TaskEntity task : pendingDeletes) {
                try {
                    Map<String, String> payload = objectMapper.readValue(task.getPayload(), new TypeReference<>() {});
                    if ("PERMANENT_DELETE".equals(task.getTaskType()) && id.toString().equals(payload.get("photoId"))) {
                        taskRepository.delete(task);
                    }
                } catch (IOException e) {
                    System.err.println("Error parsing task payload for task " + task.getId() + ": " + e.getMessage());
                }
            }
        }
    }

    public Map<String, List<Photo>> getDuplicates() {
        List<String> duplicateHashes = photoRepository.findDuplicateHashes();
        List<PhotoEntity> entities = photoRepository.findByFileHashInAndIsGarbageFalse(duplicateHashes);
        return entities.stream().map(this::convertToDto).collect(Collectors.groupingBy(Photo::fileHash));
    }

    private Photo convertToDto(PhotoEntity entity) {
        DateTimeFormatter mainDateFmt = DateTimeFormatter.ofPattern("MMM dd, yyyy");
        DateTimeFormatter fullDateTimeFmt = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm:ss");
        DateTimeFormatter groupDateFmt = DateTimeFormatter.ofPattern("EEEE, MMM dd, yyyy");
        
        String dateTaken = entity.getDateTaken() != null ? entity.getDateTaken().format(mainDateFmt) : "Unknown Date";
        String fullDateTime = entity.getDateTaken() != null ? entity.getDateTaken().format(fullDateTimeFmt) : "Unknown Date/Time";
        String groupDate = entity.getDateTaken() != null ? entity.getDateTaken().format(groupDateFmt) : "Unknown Date";
        String yearMonth = entity.getDateTaken() != null ? entity.getDateTaken().format(DateTimeFormatter.ofPattern("MMMM yyyy")) : "Other";
        
        String res = (entity.getWidth() != null && entity.getHeight() != null) ? entity.getWidth() + "x" + entity.getHeight() : "Unknown";
        
        return new Photo(
                entity.getId().toString(),
                entity.getFileName(),
                "/api/photos/" + entity.getId() + "/thumbnail",
                dateTaken,
                fullDateTime, // New field
                yearMonth,
                groupDate,
                entity.getFileSize() != null ? String.format("%.2f MB", entity.getFileSize() / (1024.0 * 1024.0)) : null,
                res,
                entity.getLocation(),
                entity.getCameraModel(),
                entity.getContentType(),
                entity.getFileHash(),
                entity.getWidth(),
                entity.getHeight(),
                entity.getFileSystemCreationDate()
        );
    }
}
