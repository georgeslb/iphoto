package org.iphoto.service;

import org.iphoto.model.DayBucketDTO;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

// Caches the day-index (day/count/cover) for Library/Space and Album detail only - Garbage/Person
// deliberately stay uncached (low-traffic pages, not worth the invalidation bookkeeping).
//
// Kept as its own component (not fields on PhotoService/AlbumService directly) so it can be
// injected into PhotoUploadService and ImmichImportService too, which need to evict a space's
// entry after creating a new photo - PhotoService already depends on PhotoUploadService, so
// putting the cache's state on PhotoService itself would create a circular dependency.
//
// Invalidation is event-based (evicted on every mutation that can change a day's count/cover),
// not time-based - anything that adds/removes/moves a photo, or changes its dateTaken (and so
// which day it's grouped under), must evict the affected space/album entries. See evictSpace/
// evictAlbum call sites: PhotoUploadService (upload + "save as new", which reuses the same
// upload path), ImmichImportService (import), PhotoService (garbage/restore/move-to-main/date
// edit), AlbumService (add/remove photos to/from an album).
public interface DayIndexCache {

    // isVideo is the photo/video type filter (null = every type) - counts differ per filter value,
    // so each (id, isVideo) pair gets its own cache entry; evictSpace/evictAlbum below still evict
    // every filter variant for that id at once, since any mutation invalidates all of them together.
    List<DayBucketDTO> getSpaceDayCounts(UUID spaceId, Boolean isVideo, Supplier<List<DayBucketDTO>> loader);

    void evictSpace(UUID spaceId);

    List<DayBucketDTO> getAlbumDayCounts(UUID albumId, Boolean isVideo, Supplier<List<DayBucketDTO>> loader);

    void evictAlbum(UUID albumId);
}
