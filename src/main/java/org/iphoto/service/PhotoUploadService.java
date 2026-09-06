package org.iphoto.service;

import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.TaskEntity;
import org.iphoto.model.UploadStatus;

import java.io.File;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public interface PhotoUploadService {

    UploadStatus processNewAsset(TaskEntity task, UUID userId, Map<String, String> payload, File tempFile, String hash) throws Exception;

    LocalDateTime extractBestCreationDate(File file, PhotoEntity photo);

    // Same as extractBestCreationDate(File, PhotoEntity), for callers that don't have (or don't
    // yet have) a PhotoEntity to hand - e.g. ImmichImportService resolving a date for an asset
    // still mid-import. Only the filename is actually needed out of the entity.
    LocalDateTime extractBestCreationDate(File file, String fileName);
}
