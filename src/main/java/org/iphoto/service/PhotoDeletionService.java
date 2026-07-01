package org.iphoto.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.AlbumEntity;
import org.iphoto.entities.DuplicateEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.TaskEntity;
import org.iphoto.model.TaskStatus;
import org.iphoto.repository.DuplicateRepository;
import org.iphoto.repository.PhotoMetadataRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

@Service
@AllArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
@Slf4j
public class PhotoDeletionService {

    private final PhotoRepository photoRepository;
    private final PhotoMetadataRepository metadataRepository;
    private final AlbumService albumService;
    private final TaskRepository taskRepository;
    private final DuplicateRepository duplicateRepository;

    public void permanentDeleteAssets(PhotoEntity photo) { // Added UserEntity parameter
        // Copy collection first
        Set<AlbumEntity> albums = new HashSet<>(photo.getAlbums());

        for (AlbumEntity album : albums) {
            albumService.removePhotosFromAlbum(photo.getUser(), album.getId(), List.of(photo.getId())); // Pass user to albumService
            // The photo is removed from the album's photos collection by albumService.
            // We don't need to save the photo here again as it will be deleted.
        }

        // Handle duplicates: This logic assumes DuplicateEntity might contain photos from different users.
        // If DuplicateEntity is user-scoped, this needs to be adjusted.
        // For now, we'll remove the specific photo from any duplicate groups it's part of.
        List<DuplicateEntity> duplicateEntities = duplicateRepository.findByPhotosIdIn(List.of(photo.getId()));
        for (DuplicateEntity duplicateEntity : duplicateEntities) {
            // Ensure we only modify duplicate groups relevant to the current user's photos
            // This is a simplification; a more robust solution might involve user-scoping DuplicateEntity
            // or more complex logic to handle shared duplicate groups.
            if (duplicateEntity.getPhotos().contains(photo)) {
                duplicateEntity.getPhotos().remove(photo);
                if (duplicateEntity.getPhotos().isEmpty()) {
                    duplicateRepository.delete(duplicateEntity);
                } else {
                    duplicateRepository.save(duplicateEntity);
                }
            }
        }

        metadataRepository.deleteById(photo.getId()); // Metadata is tied to photo ID, so delete directly
        List<TaskEntity> pendingTasks = taskRepository.findExecutableTasks(TaskStatus.PENDING.name(), LocalDateTime.now().plusYears(1), List.of(photo.getId()));
        taskRepository.deleteAll(pendingTasks);
        photoRepository.delete(photo);

    }
}
