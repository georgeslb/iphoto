package org.iphoto.model.immich;

import java.time.LocalDateTime;
import java.util.UUID;

// UPLOAD_FROM_IMMICH task payload - everything that phase needs to finish importing one asset
// without ever going back to Immich, produced by a completed SYNC_FROM_IMMICH task
// (ImmichImportService.processSyncFromImmichTask) once the asset's original file is staged under
// iphoto.storage.immich-temp and its metadata is known. Kept as one self-contained JSON blob
// (rather than the flat Map<String,String> older task payloads use) specifically so a failed
// UPLOAD_FROM_IMMICH task can be retried by re-queuing this exact payload verbatim - no re-download
// from Immich needed for a phase-2-only failure. See ImmichImportService.finalizeUploadFromImmich.
public record ImmichPendingUploadPayload(
        UUID photoId,
        UUID dayRowId,
        UUID userId,
        UUID spaceId,
        String immichTempPath,
        String fileName,
        String contentType,
        String fileHash,
        LocalDateTime dateTaken,
        String extension,
        String aperture,
        String shutterSpeed,
        String iso,
        String focalLength,
        String lensModel,
        Double latitude,
        Double longitude,
        String rawMetadataJson
) {
}
