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

// Single owner of "where on disk does a photo/thumbnail with this date live". Originals and
// thumbnails are organized as {storageRoot}/{year}/{month}/... instead of one flat directory per
// root, so a library doesn't end up with hundreds of thousands of files in one directory.
//
// Date-based (not hash/UUID-prefix-based) deliberately: the app is already date/timeline-centric
// (see TimelineMonthDTO, LibraryTimeline.jsx), so browsing the disk by month is genuinely useful.
// This is safe against a later "edit photo date" action because nothing in this codebase ever
// reconstructs a photo's path from its date after creation - PhotoEntity.originalPath/
// thumbnailPath are read back verbatim everywhere (PhotoService, EditPhotoService,
// DuplicationService, FaceDetectionService, PhotoController), so an edited date never needs to
// trigger a file move.
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class PhotoStoragePathResolver {

    IPhotoProperties iPhotoProperties;

    public Path resolveOriginalPath(java.util.UUID photoId, String extension, LocalDateTime dateTaken) throws IOException {
        Path dir = Paths.get(iPhotoProperties.getStorage().getPhotos()).resolve(subfolderFor(dateTaken));
        Files.createDirectories(dir);
        return dir.resolve(photoId + "." + extension);
    }

    public Path resolveThumbnailDir(LocalDateTime dateTaken) throws IOException {
        Path dir = Paths.get(iPhotoProperties.getStorage().getThumbnails()).resolve(subfolderFor(dateTaken));
        Files.createDirectories(dir);
        return dir;
    }

    // Whether a file that already exists at `existingPath` already lives in the folder its date
    // would put it in today - used by the (optional) flat-file backfill migration to make each
    // per-photo move idempotent/safe to re-run.
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
