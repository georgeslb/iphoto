package org.iphoto.service;

import org.iphoto.model.DayBucketDTO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

// Caches the day-index (day/count/cover) for Library/Space and Album detail only - Garbage/Person
// deliberately stay uncached (low-traffic pages, not worth the invalidation bookkeeping). A plain
// in-process map is enough here: this app runs as a single instance, not a distributed cluster.
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
@Component
public class DayIndexCache {

    private final Map<UUID, List<DayBucketDTO>> spaceDayCounts = new ConcurrentHashMap<>();
    private final Map<UUID, List<DayBucketDTO>> albumDayCounts = new ConcurrentHashMap<>();

    public List<DayBucketDTO> getSpaceDayCounts(UUID spaceId, Supplier<List<DayBucketDTO>> loader) {
        return spaceDayCounts.computeIfAbsent(spaceId, id -> loader.get());
    }

    public void evictSpace(UUID spaceId) {
        spaceDayCounts.remove(spaceId);
    }

    public List<DayBucketDTO> getAlbumDayCounts(UUID albumId, Supplier<List<DayBucketDTO>> loader) {
        return albumDayCounts.computeIfAbsent(albumId, id -> loader.get());
    }

    public void evictAlbum(UUID albumId) {
        albumDayCounts.remove(albumId);
    }
}
