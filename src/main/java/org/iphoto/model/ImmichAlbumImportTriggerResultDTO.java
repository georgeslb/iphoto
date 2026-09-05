package org.iphoto.model;

import java.util.List;

// Returned immediately by POST /api/immich/albums/import - album-photo linking itself now runs in
// the background, one IMPORT_IMMICH_ALBUM task per album (see ImmichAlbumImportService), so this
// only reports how many albums got a fresh task queued vs. already had one in flight. Per-album
// progress/outcome (linked photo count, failures) is polled separately via
// GET /api/immich/albums - see ImmichAlbumImportStatusDTO.
public record ImmichAlbumImportTriggerResultDTO(
        int albumsQueued,
        int albumsAlreadyInFlight,
        int sharedLinksCreated,
        int sharedLinksSkipped,
        List<String> warnings
) {
}
