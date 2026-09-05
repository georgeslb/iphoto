package org.iphoto.model;

import java.time.LocalDateTime;
import java.util.UUID;

// One row of GET /api/immich/albums - the live status of one Immich album's background import
// task (see ImmichAlbumImportService#listAlbumImportStatus), polled by the frontend the same way
// ImmichImportDayDTO/pollDay already polls per-day import status.
public record ImmichAlbumImportStatusDTO(
        String immichAlbumId,
        String albumName,
        UUID albumId,
        TaskStatus status,
        Integer photoCount,
        String errorMessage,
        LocalDateTime updatedAt
) {
}
