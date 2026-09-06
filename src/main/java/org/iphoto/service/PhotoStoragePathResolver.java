package org.iphoto.service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

// Single owner of "where on disk does a photo/thumbnail with this date live". Originals and
// thumbnails are organized as {storageRoot}/{year}/{month}/... instead of one flat directory per
// root, so a library doesn't end up with hundreds of thousands of files in one directory.
//
// Date-based (not hash/UUID-prefix-based) deliberately: the app is already date/day-index-centric
// (see DayBucketDTO, PhotoGrid.jsx's day-sectioned browsing), so browsing the disk by month is
// genuinely useful.
// This is safe against a later "edit photo date" action because nothing in this codebase ever
// reconstructs a photo's path from its date after creation - PhotoEntity.originalPath/
// thumbnailPath are read back verbatim everywhere (PhotoService, EditPhotoService,
// DuplicationService, FaceDetectionService, PhotoController), so an edited date never needs to
// trigger a file move.
public interface PhotoStoragePathResolver {

    Path resolveOriginalPath(UUID photoId, String extension, LocalDateTime dateTaken) throws IOException;

    Path resolveThumbnailDir(LocalDateTime dateTaken) throws IOException;

    // Whether a file that already exists at `existingPath` already lives in the folder its date
    // would put it in today - used by the (optional) flat-file backfill migration to make each
    // per-photo move idempotent/safe to re-run.
    boolean isAlreadyOrganized(Path existingPath, Path storageRoot, LocalDateTime dateTaken);
}
