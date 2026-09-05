package org.iphoto.model;

import java.time.LocalDate;

public record DayBucketDTO(LocalDate day, Long count, String coverPhotoUrl) {
}
