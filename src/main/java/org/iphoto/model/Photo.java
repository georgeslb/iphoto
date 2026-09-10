package org.iphoto.model;

import java.time.LocalDateTime;
import java.util.Set;

public record Photo(
    String id,
    String title,
    String url,
    String date, // Formatted as "MMM dd, yyyy"
    String fullDateTime, // New: Formatted as "MMM dd, yyyy HH:mm:ss"
    String yearMonth,
    String groupDate,
    String size,
    String resolution,
    String location,
    String camera,
    String contentType,
    String fileHash,
    Integer width,
    Integer height,
    LocalDateTime fileSystemCreationDate,
    // Whether the photo currently lives in its owner's MAIN space (vs. a regular Space). Drives
    // the Lightbox "Move to Main" button's visibility client-side, since capability flags moved
    // off the backend (was POST /api/photos/selected).
    Boolean inMainSpace,
    Set<Photo> duplicates
) {}
