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
            List<DuplicateEntity> duplicateEntities = duplicateRepository.findByPhotosIdIn(List.of(photo.getId()));
            for (DuplicateEntity duplicateEntity : duplicateEntities) {
                if ( duplicateEntity.getPhotos().size() <= 2) {
                    duplicateRepository.delete(duplicateEntity);
                } else {
                    duplicateEntity.getPhotos().remove(photo);
                    duplicateRepository.save(duplicateEntity);
                }
            }

            metadataRepository.deleteById(id);
            photoRepository.delete(photo);

        }

        List<TaskEntity> pendingTasks = taskRepository.findExecutableTasks(TaskStatus.PENDING.name(), LocalDateTime.now().plusYears(1), List.of(id));
        taskRepository.deleteAll(pendingTasks);
    }
}
