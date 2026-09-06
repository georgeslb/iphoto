package org.iphoto.service.immich;

import org.iphoto.model.immich.*;
import org.iphoto.service.UserService.ImmichCredentials;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

// Thin wrapper around the Immich REST API, used only during a user's one-time migration off their
// own Immich server (see specification.md / TODO.md). Each user connects their own server (see
// UserService.ImmichCredentials/resolveImmichCredentials), so every method here takes the
// credentials to use as an explicit parameter rather than binding a single base-url/api-key at
// construction time - this class holds no per-user state.
public interface ImmichClient {

    List<ImmichTimeBucketDTO> getMonthBuckets(ImmichCredentials creds, String timezoneOffset);

    ImmichAssetStatisticsDTO getAssetStatistics(ImmichCredentials creds);

    // Uses /search/metadata with day-boundary takenAfter/takenBefore rather than
    // /timeline/bucket?timeBucket=... - see ImmichSearchMetadataResponseDTO for why.
    List<ImmichAssetDetailDTO> getAssetsForDay(ImmichCredentials creds, LocalDate day, String timezoneOffset);

    // Immich's /timeline/buckets only returns MONTH buckets on current server versions (see
    // ImmichTimeBucketDTO) - to still build a real per-day index, the day-sync step (see
    // ImmichImportService.syncDayIndex) fetches every asset in a month bucket and groups them by
    // their own taken-date locally.
    List<ImmichAssetDetailDTO> getAssetsForMonth(ImmichCredentials creds, YearMonth month, String timezoneOffset);

    ImmichAssetDetailDTO getAssetDetail(ImmichCredentials creds, String assetId);

    // Streams the response body straight to a caller-chosen, deterministic path (immich-temp/
    // {photoId}.{ext} - see ImmichImportService.processSyncFromImmichTask) rather than buffering
    // in memory.
    void downloadOriginalTo(ImmichCredentials creds, String assetId, Path targetPath);

    List<ImmichAlbumSummaryDTO> listAlbums(ImmichCredentials creds, boolean ownedOnly);

    // GET /albums/{id} (AlbumResponseDto) does NOT carry an "assets" property on current Immich
    // server versions - an album's assets have to be fetched the same way the day/month sync
    // already does it for a date range (see getMonthBuckets/getAssetsForMonth), just scoped by
    // albumId instead: first list the album's time buckets, then pull each bucket's actual asset
    // ids.
    List<ImmichTimeBucketDTO> getAlbumTimeBuckets(ImmichCredentials creds, String albumId);

    // GET /timeline/bucket (singular - distinct endpoint from /timeline/buckets above) returns
    // the actual asset ids belonging to one bucket, in Immich's columnar response shape - see
    // ImmichTimeBucketAssetsDTO.
    List<String> getAlbumBucketAssetIds(ImmichCredentials creds, String albumId, String timeBucket);

    List<ImmichSharedLinkDTO> listSharedLinks(ImmichCredentials creds);
}
