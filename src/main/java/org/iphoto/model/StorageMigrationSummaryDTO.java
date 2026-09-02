package org.iphoto.model;

// Result of StorageMigrationService.migrateAllFlatPhotos() - the optional, admin-triggered
// backfill that moves pre-existing flat-stored photos/thumbnails into the year/month layout.
public record StorageMigrationSummaryDTO(int scanned, int moved, int alreadyOrganized, int failed) {
}
