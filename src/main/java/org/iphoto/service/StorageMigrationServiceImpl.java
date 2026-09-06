package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.model.StorageMigrationSummaryDTO;
import org.iphoto.properties.IPhotoProperties;
import org.iphoto.repository.PhotoRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@RequiredArgsConstructor
@Slf4j
public class StorageMigrationServiceImpl implements StorageMigrationService {

    private final PhotoRepository photoRepository;
    private final PhotoStoragePathResolver photoStoragePathResolver;
    private final IPhotoProperties iPhotoProperties;

    @Override
    public StorageMigrationSummaryDTO migrateAllFlatPhotos() {
        int scanned = 0, moved = 0, alreadyOrganized = 0, failed = 0;
        Path photosRoot = Paths.get(iPhotoProperties.getStorage().getImmichTemp());
        Path thumbnailsRoot = Paths.get(iPhotoProperties.getStorage().getThumbnails());

        Pageable pageable = PageRequest.of(0, 200);
        Slice<PhotoEntity> slice;
        do {
            slice = photoRepository.findAll(pageable);
            for (PhotoEntity photo : slice.getContent()) {
                scanned++;
                try {
                    boolean movedOriginal = migrateOriginal(photo, photosRoot);
                    boolean movedThumbnail = migrateThumbnail(photo, thumbnailsRoot);
                    if (movedOriginal || movedThumbnail) {
                        moved++;
                        photoRepository.save(photo);
                    } else {
                        alreadyOrganized++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("Failed to migrate storage layout for photo {}", photo.getId(), e);
                }
            }
            pageable = slice.nextPageable();
        } while (slice.hasNext());

        return new StorageMigrationSummaryDTO(scanned, moved, alreadyOrganized, failed);
    }

    private boolean migrateOriginal(PhotoEntity photo, Path photosRoot) throws IOException {
        if (photo.getOriginalPath() == null) return false;
        Path current = Paths.get(photo.getOriginalPath());
        if (!Files.exists(current)) return false;
        if (photoStoragePathResolver.isAlreadyOrganized(current, photosRoot, photo.getDateTaken())) return false;

        String extension = FilenameUtils.getExtension(photo.getOriginalPath());
        Path target = photoStoragePathResolver.resolveOriginalPath(photo.getId(), extension, photo.getDateTaken());
        Files.move(current, target);
        photo.setOriginalPath(target.toString());
        return true;
    }

    private boolean migrateThumbnail(PhotoEntity photo, Path thumbnailsRoot) throws IOException {
        if (photo.getThumbnailPath() == null) return false;
        Path current = Paths.get(photo.getThumbnailPath());
        if (!Files.exists(current)) return false;
        if (photoStoragePathResolver.isAlreadyOrganized(current, thumbnailsRoot, photo.getDateTaken())) return false;

        Path targetDir = photoStoragePathResolver.resolveThumbnailDir(photo.getDateTaken());
        Path target = targetDir.resolve(current.getFileName());
        Files.move(current, target);
        photo.setThumbnailPath(target.toString());
        return true;
    }
}
