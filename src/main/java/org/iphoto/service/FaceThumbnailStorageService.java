package org.iphoto.service;

import java.io.IOException;
import java.time.LocalDateTime;

// Filesystem storage for face-thumbnail JPEGs (see FaceEntity.thumbnailPath's own comment for why
// these no longer live as a BYTEA blob in the faces table). Deliberately its own small service
// rather than folded into FaceDetectionServiceImpl/FaceServiceImpl - FaceThumbnailMigrationService
// (backfilling pre-existing DB-blob rows) needs the exact same save/delete logic those do.
public interface FaceThumbnailStorageService {

    // Writes thumbnailBytes to a new file under iphoto.storage.faceThumbnails (same
    // {year}/{month} subfolder convention PhotoStoragePathResolver uses, keyed by the owning
    // photo's dateTaken so a face's thumbnail sits alongside thumbnails from the same time period)
    // and returns its absolute path, ready to store on FaceEntity.thumbnailPath.
    String save(byte[] thumbnailBytes, LocalDateTime photoDateTaken) throws IOException;

    // Best-effort delete of a previously-saved thumbnail file - a no-op (not an error) if
    // thumbnailPath is null or the file is already gone, since callers use this from delete paths
    // where "the file isn't there anymore" is never itself a failure worth propagating.
    void delete(String thumbnailPath);
}
