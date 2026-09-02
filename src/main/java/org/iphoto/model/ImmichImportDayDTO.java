package org.iphoto.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record ImmichImportDayDTO(
        UUID id,
        LocalDate day,
        Integer immichAssetCount,
        Integer queuedCount,
        Integer importedCount,
        Integer skippedCount,
        Integer failedCount,
        ImmichImportDayStatus status,
        LocalDateTime lastAttemptedAt,
        LocalDateTime lastSyncedAt
) {
}
