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

    private final Map<UUID, List<DayBucketDTO>> spaceDayCounts = new ConcurrentHashMap<>();
    private final Map<UUID, List<DayBucketDTO>> albumDayCounts = new ConcurrentHashMap<>();

    @Override
    public List<DayBucketDTO> getSpaceDayCounts(UUID spaceId, Supplier<List<DayBucketDTO>> loader) {
        return spaceDayCounts.computeIfAbsent(spaceId, id -> loader.get());
    }

    @Override
    public void evictSpace(UUID spaceId) {
        spaceDayCounts.remove(spaceId);
    }

    @Override
    public List<DayBucketDTO> getAlbumDayCounts(UUID albumId, Supplier<List<DayBucketDTO>> loader) {
        return albumDayCounts.computeIfAbsent(albumId, id -> loader.get());
    }

    @Override
    public void evictAlbum(UUID albumId) {
        albumDayCounts.remove(albumId);
    }
}
