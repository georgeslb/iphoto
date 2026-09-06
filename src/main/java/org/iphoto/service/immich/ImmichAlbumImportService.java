package org.iphoto.service.immich;

import org.iphoto.entities.TaskEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.ImmichAlbumImportStatusDTO;
import org.iphoto.model.ImmichAlbumImportTriggerResultDTO;

import java.util.List;

// Recreates Immich albums (and their album-level shared links) in iphoto. Album *creation* and
// the shared-link pass run synchronously on the triggering request (cheap - local DB only, plus
// the one listAlbums/listSharedLinks call each), but the actual photo-linking work per album -
// fetching /timeline/buckets then /timeline/bucket per bucket, potentially many Immich round
// trips per album, across potentially many albums - is queued as one IMPORT_IMMICH_ALBUM task per
// album (see TaskWorkerManager) instead of running inline, so a request with many albums doesn't
// block on however long Immich takes to answer all of them.
public interface ImmichAlbumImportService {

    ImmichAlbumImportTriggerResultDTO triggerAlbumImport(UserEntity user);

    // Called by TaskWorkerManager for each IMPORT_IMMICH_ALBUM task - the actual photo-linking
    // work deferred out of triggerAlbumImport above.
    void processAlbumImportTask(TaskEntity task);

    // Backs GET /api/immich/albums - the live per-album status list the frontend polls the same
    // way it already polls per-day import status (see ImmichImportDayDTO/pollDay).
    List<ImmichAlbumImportStatusDTO> listAlbumImportStatus(UserEntity user);
}
