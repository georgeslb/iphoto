package org.iphoto.model;

import java.util.List;

public record ImmichAlbumImportSummaryDTO(
        int albumsCreated,
        int albumsUpdated,
        int photosLinked,
        int photosSkippedNotYetImported,
        int sharedLinksCreated,
        int sharedLinksSkipped,
        List<String> warnings
) {
}
