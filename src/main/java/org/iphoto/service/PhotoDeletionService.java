package org.iphoto.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.AlbumEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.repository.PhotoMetadataRepository;
import org.iphoto.repository.PhotoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.*;

@Service
@AllArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
@Slf4j
public class PhotoDeletionService {

    private final PhotoRepository photoRepository;
    private final PhotoMetadataRepository metadataRepository;
    private final AlbumService albumService;

    public void permanentDeleteAssets(UUID id) throws IOException {
        Optional<PhotoEntity> optionalPhoto = photoRepository.findById(id);
        if (optionalPhoto.isPresent()) {
            PhotoEntity photo = optionalPhoto.get();
            // Copy collection first
            Set<AlbumEntity> albums = new HashSet<>(photo.getAlbums());

            for (AlbumEntity album : albums) {
                albumService.removePhotosFromAlbum(album.getId(), List.of(photo.getId()));
                photoRepository.save(photo);
            }

            metadataRepository.deleteById(id);

            photoRepository.delete(photo);

        }

/*        List<TaskEntity> pendingDeletes = taskRepository.findExecutableTasks(TaskStatus.PENDING.name(), LocalDateTime.now().plusYears(1));
        for (TaskEntity task : pendingDeletes) {
            try {
                Map<String, String> payload = objectMapper.readValue(task.getPayload(), new TypeReference<>() {});
                if ("PERMANENT_DELETE".equals(task.getTaskType()) && id.toString().equals(payload.get("photoId"))) {
                    taskRepository.delete(task);
                }
            } catch (IOException e) {
                log.error("Error parsing task payload for task {}", task.getId(), e);
            }
        }*/
    }
}
