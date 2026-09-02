package org.iphoto.model;

import lombok.Getter;

@Getter
public enum TaskType {
    PERMANENT_DELETE (false, 0),
    PROCESS_UPLOAD (false, 0),
    HASH_IMAGE(true, 1),
    CHECK_DUPLICATION(true, 2),
    ISOLATE_FACES(true, 1),
    FACE_RECOGNITION (false, 2),
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
    SYNC_FROM_IMMICH(false, 0),
    // Phase 2: generate the thumbnail, commit the PhotoEntity/PhotoMetadataEntity rows, and only
    // once that's actually committed, move the staged file from immich-temp into its final
    // folder - never the other way around, so a DB failure can never leave an orphaned file
    // sitting in the final folder (which would permanently block a future retry, since the move
    // there uses no REPLACE_EXISTING). Idempotent/resumable: retrying after the DB commit
    // succeeded but the move failed just re-attempts the move, without re-inserting the row.
    UPLOAD_FROM_IMMICH(false, 0),
    ;

    private final boolean canBeReplayed;
    private final int order;

    TaskType(boolean canBeReplayed, int order) {
        this.canBeReplayed = canBeReplayed;
        this.order = order;
    }

}
