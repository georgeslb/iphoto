package org.iphoto.model.immich;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// GET /timeline/buckets?size=MONTH response item. Immich's /timeline/buckets endpoint only
// supports MONTH-granularity buckets on current server versions (the DAY bucket size that used to
// exist was dropped server-side - the official web client now buckets by month and derives
// per-day groupings client-side from each asset's own timestamp within a month's assets). So this
// is a *month* bucket, not a day bucket, despite the field being named "day" originally in the
// Immich source - see ImmichClient.getMonthBuckets/ImmichImportService.syncDayIndex for how real
// per-day rows get built from this. BEST-EFFORT SHAPE - verify against a live call: expected as
// an array of {timeBucket, count}, timeBucket being a date string (e.g. "2024-01-01" or a full
// ISO-8601 midnight timestamp, first-of-month, depending on Immich version).
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImmichTimeBucketDTO(String timeBucket, Long count) {
}
