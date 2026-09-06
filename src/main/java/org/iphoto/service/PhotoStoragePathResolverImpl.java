package org.iphoto.service;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
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
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class PhotoStoragePathResolverImpl implements PhotoStoragePathResolver {

    IPhotoProperties iPhotoProperties;

    @Override
    public Path resolveOriginalPath(UUID photoId, String extension, LocalDateTime dateTaken) throws IOException {
        Path dir = Paths.get(iPhotoProperties.getStorage().getPhotos()).resolve(subfolderFor(dateTaken));
        Files.createDirectories(dir);
        return dir.resolve(photoId + "." + extension);
    }

    @Override
    public Path resolveThumbnailDir(LocalDateTime dateTaken) throws IOException {
        Path dir = Paths.get(iPhotoProperties.getStorage().getThumbnails()).resolve(subfolderFor(dateTaken));
        Files.createDirectories(dir);
        return dir;
    }

    @Override
    public boolean isAlreadyOrganized(Path existingPath, Path storageRoot, LocalDateTime dateTaken) {
        Path expectedDir = storageRoot.resolve(subfolderFor(dateTaken));
        Path actualDir = existingPath.getParent();
        return actualDir != null && actualDir.equals(expectedDir);
    }

    private String subfolderFor(LocalDateTime dateTaken) {
        LocalDateTime effective = dateTaken != null ? dateTaken : LocalDateTime.now();
        return "%04d/%02d".formatted(effective.getYear(), effective.getMonthValue());
    }
}
