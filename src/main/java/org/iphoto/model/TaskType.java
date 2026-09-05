package org.iphoto.model;

import lombok.Getter;

@Getter
public enum TaskType {
    PERMANENT_DELETE (false, 0, false),
    PROCESS_UPLOAD (false, 0, false),
    HASH_IMAGE(true, 1, false),
    // Global admin on/off switch, not a per-photo task - exactly one row ever exists, updated in
    // place on every admin toggle rather than a new row per click (see AdminService#
    // activateHeavyTasks). canBeReplayed=false: AdminService#updateImageTaskToDo replays
    // canBeReplayed types once per photo, which would be meaningless (and actively harmful - see
    // that method's own comment) for a single global row with photoId always null.
    ACTIVATE_HEAVY_TASKS(false, 3, false),
    CHECK_DUPLICATION(true, 2, true),
    ISOLATE_FACES(true, 1, true),
    FACE_RECOGNITION (false, 2, true),
    // Immich asset import is split into two chained phases (see ImmichImportService), one task
    // type per phase - so a phase-2-only failure (a DB/disk problem local to this app) never has
    // to re-hit the Immich server, and so the "asset" file is never copied into its permanent
    // location until the DB row for it is actually, durably committed (see UPLOAD_FROM_IMMICH).
    // photoId on both is the Immich asset's own UUID (not a freshly-generated one, same
    // `PhotoEntity.id` has-no-@GeneratedValue mechanism PROCESS_UPLOAD/UploadController already
    // relies on), carried through both tasks so retries can always find "the" task for one asset.
    //
    // Phase 1: fetch the asset + its metadata from Immich, download the original into
    // iphoto.storage.immich-temp (its final year/month folder is deliberately NOT touched yet),
    // and queue one UPLOAD_FROM_IMMICH task ~1 minute later carrying everything phase 2 needs.
    SYNC_FROM_IMMICH(true, 1, false),
    // Phase 2: generate the thumbnail, commit the PhotoEntity/PhotoMetadataEntity rows, and only
    // once that's actually committed, move the staged file from immich-temp into its final
    // folder - never the other way around, so a DB failure can never leave an orphaned file
    // sitting in the final folder (which would permanently block a future retry, since the move
    // there uses no REPLACE_EXISTING). Idempotent/resumable: retrying after the DB commit
    // succeeded but the move failed just re-attempts the move, without re-inserting the row.
    UPLOAD_FROM_IMMICH(true, 2, false),
    // Immich album import, one task per album (see ImmichAlbumImportService#triggerAlbumImport) -
    // queued synchronously (cheap: only a local find-or-create of the AlbumEntity row, no Immich
    // API calls beyond the single listAlbums the whole batch already made) so the actual per-album
    // work - fetching /timeline/buckets then /timeline/bucket per bucket, potentially many Immich
    // round trips, across potentially many albums - runs on the light task pool instead of
    // blocking the triggering HTTP request. photoId carries the *local* AlbumEntity id, not a
    // photo - same generic "subject of this task" reuse of that column as PROCESS_UPLOAD/
    // HASH_IMAGE etc. do for photos. canBeReplayed=false: not a per-photo task type, replaying it
    // via AdminService#updateImageTaskToDo would be meaningless.
    IMPORT_IMMICH_ALBUM(false, 2, false),
    ;

    private final boolean canBeReplayed;
    private final int order;
    private final boolean heavy;

    TaskType(boolean canBeReplayed, int order, boolean heavy) {
        this.canBeReplayed = canBeReplayed;
        this.order = order;
        this.heavy = heavy;
    }

}
