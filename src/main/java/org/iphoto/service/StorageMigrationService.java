package org.iphoto.service;

import org.iphoto.model.StorageMigrationSummaryDTO;

// Optional, admin-triggered, idempotent backfill: moves any photo/thumbnail still sitting in the
// old flat storage layout into the new {root}/{year}/{month}/ layout (see
// PhotoStoragePathResolver). Deliberately NOT run automatically at startup - a global file-move
// sweep against a real photo library is exactly the kind of surprising background I/O an admin
// should opt into explicitly. Safe to re-run: a photo already in its expected folder is a no-op.
public interface StorageMigrationService {

    StorageMigrationSummaryDTO migrateAllFlatPhotos();
}
