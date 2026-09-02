package org.iphoto.model.immich;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// GET /albums list item (AlbumResponseDto, partial). BEST-EFFORT SHAPE - verify live.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImmichAlbumSummaryDTO(String id, String albumName, String description, Integer assetCount) {
}
