package org.iphoto.model;

// Safety check surfaced on the admin Immich-import page before the user deletes their Immich
// server: immichTotal is Immich's own reported total asset count, bucketedTotal is the sum of
// per-day counts the day-index actually knows about (from /timeline/buckets), and
// unaccountedForCount flags assets that exist on Immich but have no day bucket at all (e.g. no
// usable date) - those would otherwise be silently missed by day-by-day import.
public record ImmichCoverageDTO(long immichTotal, long bucketedTotal, long importedTotal, long unaccountedForCount) {
}
