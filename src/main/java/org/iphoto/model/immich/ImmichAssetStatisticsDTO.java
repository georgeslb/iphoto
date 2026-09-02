package org.iphoto.model.immich;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// GET /assets/statistics response (AssetStatsResponseDto). BEST-EFFORT SHAPE - verify live.
// Used only for the coverage check (ImmichImportService) comparing Immich's true total asset
// count against what the day-bucket index has accounted for.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImmichAssetStatisticsDTO(Long images, Long videos, Long total) {
}
