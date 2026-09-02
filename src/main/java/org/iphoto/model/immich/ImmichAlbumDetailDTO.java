package org.iphoto.model.immich;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

// GET /albums/{id} response (AlbumResponseDto, partial). BEST-EFFORT SHAPE - verify live,
// especially whether "assets" items are full AssetResponseDto objects (fine either way, only
// id is read) or something lighter.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImmichAlbumDetailDTO(String id, String albumName, List<ImmichAssetRefDTO> assets) {
}
