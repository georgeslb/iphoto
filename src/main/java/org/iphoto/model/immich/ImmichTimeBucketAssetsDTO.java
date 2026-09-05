package org.iphoto.model.immich;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

// GET /timeline/bucket?albumId=...&timeBucket=... response (TimelineResponseDto, partial).
// Immich returns this in a columnar shape - one array per asset field (id, ownerId, ...), all
// the same length, index i describing one asset - rather than an array of asset objects. Only
// the id column is read here.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImmichTimeBucketAssetsDTO(List<String> id) {
}
