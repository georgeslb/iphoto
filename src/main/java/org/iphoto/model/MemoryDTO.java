package org.iphoto.model;

import java.time.LocalDate;
import java.util.List;

// One entry in Library's "N months/years ago" bar - see PhotoService.getMemories and
// IPhotoProperties.Memories.monthsAgo/photosPerBlock for the configurable offsets/photo count.
// `photos` holds up to photosPerBlock random photos for that day, auto-cycled in the frontend's
// one block instead of a single static photo.
public record MemoryDTO(int monthsAgo, LocalDate day, List<Photo> photos) {
}
