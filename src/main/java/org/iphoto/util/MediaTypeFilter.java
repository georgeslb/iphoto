package org.iphoto.util;

// Shared parsing for the photo/video type filter (?type=photo|video query param) used by the
// Library/Space/Album/Garbage day-index endpoints - extracted so PhotoController and
// AlbumController agree on exactly the same values rather than each re-implementing the mapping.
public final class MediaTypeFilter {

    private MediaTypeFilter() {
    }

    // null (absent/unrecognized `type`) means "every type", matching the pre-filter behavior -
    // callers thread this straight into PhotoEntity.isVideo comparisons, where null disables the
    // predicate entirely (see PhotoRepository's "(:isVideo IS NULL OR p.isVideo = :isVideo)").
    public static Boolean resolveIsVideo(String type) {
        if ("video".equalsIgnoreCase(type)) return Boolean.TRUE;
        if ("photo".equalsIgnoreCase(type)) return Boolean.FALSE;
        return null;
    }
}
