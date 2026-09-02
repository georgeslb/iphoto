package org.iphoto.model.immich;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

// POST /search/metadata response (SearchResponseDto). BEST-EFFORT SHAPE - verify live. Chosen
// over /timeline/bucket for per-day asset listing because it's the documented, conventional
// list-response shape (vs. /timeline/bucket's less certain, version-sensitive columnar format) -
// see ImmichClient.getAssetsForDay.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImmichSearchMetadataResponseDTO(Assets assets) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Assets(List<ImmichAssetDetailDTO> items, String nextPage) {
    }
}
