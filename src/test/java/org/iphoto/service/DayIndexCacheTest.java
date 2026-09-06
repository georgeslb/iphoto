package org.iphoto.service;

import org.iphoto.AbstractIntegrationTest;
import org.iphoto.model.DayBucketDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// Direct service-level test - DayIndexCache has no HTTP endpoint of its own, it's an in-process
// cache consumed by PhotoService/AlbumService (see its own class comment).
class DayIndexCacheTest extends AbstractIntegrationTest {

    @Autowired
    private DayIndexCache dayIndexCache;

    @Test
    void getSpaceDayCountsOnlyInvokesTheLoaderOnceUntilEvicted() {
        UUID spaceId = UUID.randomUUID();
        List<DayBucketDTO> loaded = List.of(new DayBucketDTO(LocalDate.of(2024, 1, 1), 3L));
        AtomicInteger loaderCalls = new AtomicInteger();

        List<DayBucketDTO> first = dayIndexCache.getSpaceDayCounts(spaceId, () -> { loaderCalls.incrementAndGet(); return loaded; });
        List<DayBucketDTO> second = dayIndexCache.getSpaceDayCounts(spaceId, () -> { loaderCalls.incrementAndGet(); return loaded; });

        assertThat(first).isEqualTo(loaded);
        assertThat(second).isEqualTo(loaded);
        assertThat(loaderCalls).hasValue(1);

        dayIndexCache.evictSpace(spaceId);
        dayIndexCache.getSpaceDayCounts(spaceId, () -> { loaderCalls.incrementAndGet(); return loaded; });
        assertThat(loaderCalls).hasValue(2);
    }

    @Test
    void getAlbumDayCountsOnlyInvokesTheLoaderOnceUntilEvicted() {
        UUID albumId = UUID.randomUUID();
        List<DayBucketDTO> loaded = List.of(new DayBucketDTO(LocalDate.of(2024, 2, 2), 1L));
        AtomicInteger loaderCalls = new AtomicInteger();

        dayIndexCache.getAlbumDayCounts(albumId, () -> { loaderCalls.incrementAndGet(); return loaded; });
        dayIndexCache.getAlbumDayCounts(albumId, () -> { loaderCalls.incrementAndGet(); return loaded; });
        assertThat(loaderCalls).hasValue(1);

        dayIndexCache.evictAlbum(albumId);
        dayIndexCache.getAlbumDayCounts(albumId, () -> { loaderCalls.incrementAndGet(); return loaded; });
        assertThat(loaderCalls).hasValue(2);
    }
}
