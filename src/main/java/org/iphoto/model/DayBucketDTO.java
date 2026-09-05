package org.iphoto.model;

import org.iphoto.repository.PhotoRepository.DayCountProjection;

import java.time.LocalDate;
import java.util.List;

public record DayBucketDTO(LocalDate day, Long count) {

    public static List<DayBucketDTO> from(List<? extends DayCountProjection> counts) {
        return counts.stream().map(c -> new DayBucketDTO(c.getDay(), c.getCount())).toList();
    }
}
