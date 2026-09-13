package org.iphoto.model;

// Result of FaceThumbnailMigrationService.migrateAll() - the optional, admin-triggered backfill
// that moves any face thumbnail still stored as a DB BYTEA blob (faces.face_thumbnail) onto disk
// (faces.thumbnail_path), re-cropping/recompressing it through the same size/JPEG pipeline newly
// detected faces use along the way - closing the "already-stored face thumbnails from before the
// oversized-PNG fix remain oversized until re-processed" gap noted in TODO.md, not just relocating
// the existing bytes as-is.
public record FaceThumbnailMigrationSummaryDTO(int scanned, int migrated, int failed) {
}
