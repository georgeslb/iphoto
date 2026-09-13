package org.iphoto.service;

import org.iphoto.model.DayBucketDTO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

// A plain in-process map is enough here: this app runs as a single instance, not a distributed
// cluster. See DayIndexCache for the caching/invalidation contract this fulfills.
@Component
public class DayIndexCacheImpl implements DayIndexCache {

    // Keyed by "<id>:<all|photo|video>" rather than a nested map - evictSpace/evictAlbum then just
    // need a prefix match over a flat key set instead of reaching into a per-id sub-map.
    private final Map<String, List<DayBucketDTO>> spaceDayCounts = new ConcurrentHashMap<>();
    private final Map<String, List<DayBucketDTO>> albumDayCounts = new ConcurrentHashMap<>();

    private static String key(UUID id, Boolean isVideo) {
        String type = isVideo == null ? "all" : (isVideo ? "video" : "photo");
        return id + ":" + type;
    }

    @Override
    public List<DayBucketDTO> getSpaceDayCounts(UUID spaceId, Boolean isVideo, Supplier<List<DayBucketDTO>> loader) {
        return spaceDayCounts.computeIfAbsent(key(spaceId, isVideo), k -> loader.get());
    }

    @Override
    public void evictSpace(UUID spaceId) {
        String prefix = spaceId + ":";
        spaceDayCounts.keySet().removeIf(k -> k.startsWith(prefix));
    }

    @Override
    public List<DayBucketDTO> getAlbumDayCounts(UUID albumId, Boolean isVideo, Supplier<List<DayBucketDTO>> loader) {
        return albumDayCounts.computeIfAbsent(key(albumId, isVideo), k -> loader.get());
    }

    @Override
    public void evictAlbum(UUID albumId) {
        String prefix = albumId + ":";
        albumDayCounts.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
