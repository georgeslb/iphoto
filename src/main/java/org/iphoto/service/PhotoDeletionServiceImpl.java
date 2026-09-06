package org.iphoto.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.AlbumEntity;
import org.iphoto.repository.PhotoMetadataRepository;
import org.iphoto.repository.PhotoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@AllArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW)
@Slf4j
public class PhotoDeletionServiceImpl implements PhotoDeletionService {

    private final PhotoRepository photoRepository;
    private final PhotoMetadataRepository metadataRepository;
    private final AlbumService albumService;
    private final TaskMapper taskMapper;

    @Override
    public void permanentDeleteAssets(UUID photoId) {
        photoRepository.findById(photoId).ifPresent(
                photo -> {
                    // Copy collection first
                    Set<AlbumEntity> albums = new HashSet<>(photo.getAlbums());

                    for (AlbumEntity album : albums) {
                        albumService.removePhotosFromAlbum(album.getId(), List.of(photo.getId())); // Pass user to albumService
                        // The photo is removed from the album's photos collection by albumService.
                        // We don't need to save the photo here again as it will be deleted.
                    }

                    // Handle duplicates: This logic assumes DuplicateEntity might contain photos from different users.
                    // If DuplicateEntity is user-scoped, this needs to be adjusted.
                    // For now, we'll remove the specific photo from any duplicate groups it's part of.
//                    List<DuplicateEntity> duplicateEntities = duplicateRepository.findByPhotoDuplicatesPhotosIdIn(List.of(photo.getId()));
//                    for (DuplicateEntity duplicateEntity : duplicateEntities) {
//                        if (duplicateEntity.getPhotos().contains(photo)) {
//                            duplicateEntity.getPhotos().remove(photo);
//                            if (duplicateEntity.getPhotos().isEmpty()) {
//                                duplicateRepository.delete(duplicateEntity);
//                            } else {
//                                duplicateRepository.save(duplicateEntity);
//                            }
//                        }
//                    }

                    metadataRepository.deleteById(photo.getId());
                    taskMapper.updateTasksToAction(TaskMapper.Action.PHOTO_PERMANENTLY_DELETED, photo.getId());
                    photoRepository.delete(photo);
                }
        );
    }
}
