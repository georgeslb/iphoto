package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.properties.IPhotoProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaceThumbnailStorageServiceImpl implements FaceThumbnailStorageService {

    private final IPhotoProperties iPhotoProperties;

    @Override
    public String save(byte[] thumbnailBytes, LocalDateTime photoDateTaken) throws IOException {
        Path dir = Paths.get(iPhotoProperties.getStorage().getFaceThumbnails()).resolve(subfolderFor(photoDateTaken));
        Files.createDirectories(dir);
        // A fresh random name, not the owning FaceEntity's own id - the id isn't assigned yet at
        // the point FaceDetectionServiceImpl calls this (the row is only saved right afterward),
        // and nothing else needs the filename to match the face id.
        Path target = dir.resolve(UUID.randomUUID() + ".jpg");
        Files.write(target, thumbnailBytes);
        return target.toString();
    }

    @Override
    public void delete(String thumbnailPath) {
        if (thumbnailPath == null) {
            return;
        }
        try {
            Files.deleteIfExists(Paths.get(thumbnailPath));
        } catch (IOException e) {
            log.warn("Failed to delete face thumbnail file {}", thumbnailPath, e);
        }
    }

    private String subfolderFor(LocalDateTime dateTaken) {
        LocalDateTime effective = dateTaken != null ? dateTaken : LocalDateTime.now();
        return "%04d/%02d".formatted(effective.getYear(), effective.getMonthValue());
    }
}
