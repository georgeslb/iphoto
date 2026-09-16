package org.iphoto.service;

import org.iphoto.model.FaceThumbnailMigrationSummaryDTO;

// Optional, admin-triggered, idempotent backfill: moves any face thumbnail still sitting in the
// legacy faces.face_thumbnail BYTEA column onto disk (faces.thumbnail_path) - see
// FaceEntity.thumbnailPath's own comment. Deliberately NOT run automatically at startup, same
// reasoning as StorageMigrationService - a sweep over every face row is exactly the kind of
// surprising background I/O an admin should opt into explicitly. Safe to re-run: a face that
// already has a thumbnailPath is simply not selected again.
public interface FaceThumbnailMigrationService {

    FaceThumbnailMigrationSummaryDTO migrateAll();
}
