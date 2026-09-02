package org.iphoto.model.immich;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

// GET /shared-links list item (SharedLinkResponseDto, partial). BEST-EFFORT SHAPE - verify live,
// in particular: whether an asset-level (non-album) link really has `album: null` with `assets`
// populated instead, as assumed here, or a differently-shaped object entirely - see
// ImmichAlbumImportService, which skips-with-warning anything it can't confidently map rather
// than guessing.
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImmichSharedLinkDTO(
        String id,
        String type,
        String key,
        String expiresAt,
        ImmichAlbumRefDTO album,
        List<ImmichAssetRefDTO> assets
) {
    public boolean isAlbumLevel() {
        return album != null && album.id() != null;
    }
}
