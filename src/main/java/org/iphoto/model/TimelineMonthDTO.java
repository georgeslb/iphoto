package org.iphoto.model;

// One bucket in the Library's month/year index (see PhotoService.getTimeline) — every calendar
// month that has at least one non-garbage Main Space photo, with how many, plus one of that
// month's own photos as a cover thumbnail (a random one, refreshed on every call — see
// PhotoRepository.findMonthlyPhotoCountsWithCoverBySpaceId). Months with zero photos are simply
// absent from the list rather than returned with count 0: both consumers (the "Memories" strip
// and the browse-by-month list, see LibraryTimeline.jsx) rely on presence alone to know a month
// has content, without a second query.
public record TimelineMonthDTO(Integer year, Integer month, Long count, String coverPhotoUrl) {
}
