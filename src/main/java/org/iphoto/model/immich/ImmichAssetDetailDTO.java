package org.iphoto.model.immich;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// AssetResponseDto (partial - only the fields the import actually needs). BEST-EFFORT SHAPE -
// verify against a live call before trusting for a real import (see ImmichClient).
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImmichAssetDetailDTO(
        String id,
        String originalFileName,
        String originalMimeType,
        // Both provided as fallbacks for resolving dateTaken - prefer exifInfo.dateTimeOriginal
        // first (see ImmichImportService), same multi-fallback spirit as
        // PhotoUploadService.extractBestCreationDate.
        String localDateTime,
        String fileCreatedAt,
        String checksum,
        Boolean isFavorite,
        ImmichExifDTO exifInfo
) {
}
