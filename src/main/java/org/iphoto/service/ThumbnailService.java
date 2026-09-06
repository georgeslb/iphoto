package org.iphoto.service;

import java.io.IOException;
import java.time.LocalDateTime;

public interface ThumbnailService {

    // Used when creating a thumbnail for a photo whose date (and therefore year/month folder) is
    // known - the normal case for both regular uploads and Immich-imported assets.
    String generateThumbnail(String originalPath, String fileId, String contentType, LocalDateTime dateTaken) throws IOException;

    // Used when regenerating a thumbnail for an existing photo that was just edited in place
    // (crop/rotate/"overwrite original") - it must land back in whichever folder the thumbnail
    // already lives in, never move the photo just because it was edited.
    String regenerateThumbnailInPlace(String originalPath, String fileId, String contentType, String existingThumbnailPath) throws IOException;
}
