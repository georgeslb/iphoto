package org.iphoto.service.immich;

import lombok.extern.slf4j.Slf4j;
import org.iphoto.exception.ImmichApiException;
import org.iphoto.model.immich.*;
import org.iphoto.service.UserService.ImmichCredentials;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

// Thin wrapper around the Immich REST API, used only during a user's one-time migration off their
// own Immich server (see specification.md / TODO.md). Each user connects their own server (see
// UserService.ImmichCredentials/resolveImmichCredentials), so every method here takes the
// credentials to use as an explicit parameter rather than binding a single base-url/api-key at
// construction time - this class holds no per-user state.
// NOTE: several response shapes below are BEST-EFFORT, built from Immich's documented API rather
// than a live call against a real server - each DTO/method that's uncertain says so. Verify
// against a real server and fix up field names/paths here if a live call disagrees - this class
// is deliberately the only place that knows Immich's wire format, so a fix here is a small,
// isolated patch.
@Service
@Slf4j
public class ImmichClient {

    // Bounds concurrent outbound calls to a user's Immich server: TaskWorkerManager's poller has
    // no throttle of its own, so without this a 200-asset day could fire ~200 simultaneous
    // requests. Shared across all users - fine, since imports are expected to be occasional/manual,
    // not constantly running for many users at once.
    private static final int MAX_CONCURRENT_CALLS = 8;
    private static final int MAX_SEARCH_PAGES = 40;
    private static final int SEARCH_PAGE_SIZE = 250;

    private final RestClient restClient = RestClient.create();
    private final Semaphore throttle = new Semaphore(MAX_CONCURRENT_CALLS);

    public List<ImmichTimeBucketDTO> getMonthBuckets(ImmichCredentials creds, String timezoneOffset) {
        return throttled(() -> {
            List<ImmichTimeBucketDTO> buckets = restClient.get()
                    .uri(uri(creds, "/timeline/buckets", b -> b.queryParam("size", "MONTH").queryParam("timezoneOffset", timezoneOffset)))
                    .header("x-api-key", creds.apiKey())
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<ImmichTimeBucketDTO>>() {});
            return buckets != null ? buckets : List.of();
        });
    }

    public ImmichAssetStatisticsDTO getAssetStatistics(ImmichCredentials creds) {
        return throttled(() -> restClient.get()
                .uri(uri(creds, "/assets/statistics", b -> b))
                .header("x-api-key", creds.apiKey())
                .retrieve()
                .body(ImmichAssetStatisticsDTO.class));
    }

    // Uses /search/metadata with day-boundary takenAfter/takenBefore rather than
    // /timeline/bucket?timeBucket=... - see ImmichSearchMetadataResponseDTO for why.
    public List<ImmichAssetDetailDTO> getAssetsForDay(ImmichCredentials creds, LocalDate day, String timezoneOffset) {
        String takenAfter = isoInstant(day.atStartOfDay(), timezoneOffset);
        String takenBefore = isoInstant(day.plusDays(1).atStartOfDay().minusNanos(1), timezoneOffset);
        return searchMetadata(creds, takenAfter, takenBefore, "day " + day);
    }

    // Immich's /timeline/buckets only returns MONTH buckets on current server versions (see
    // ImmichTimeBucketDTO) - to still build a real per-day index, the day-sync step (see
    // ImmichImportService.syncDayIndex) fetches every asset in a month bucket and groups them by
    // their own taken-date locally. Heavier than a plain day-count would be, but there's no
    // day-count-only endpoint left to ask Immich for.
    public List<ImmichAssetDetailDTO> getAssetsForMonth(ImmichCredentials creds, YearMonth month, String timezoneOffset) {
        String takenAfter = isoInstant(month.atDay(1).atStartOfDay(), timezoneOffset);
        String takenBefore = isoInstant(month.plusMonths(1).atDay(1).atStartOfDay().minusNanos(1), timezoneOffset);
        return searchMetadata(creds, takenAfter, takenBefore, "month " + month);
    }

    // Immich's /search/metadata takenAfter/takenBefore fields are validated as ISO 8601 datetime
    // strings - LocalDateTime.format(ISO_LOCAL_DATE_TIME) doesn't qualify on two counts: it never
    // carries a timezone/offset (so a "local midnight" day boundary was being sent with no
    // indication of which timezone that midnight is in - the timezoneOffset parameter was
    // accepted but silently unused here), and it drops the seconds field entirely when it's zero
    // (e.g. "2024-01-15T00:00", not "...T00:00:00"). Converting to a real Instant and using its
    // ISO-8601 toString (always "yyyy-MM-ddTHH:mm:ss[.SSS]Z", seconds always present) fixes both.
    private static String isoInstant(LocalDateTime localDateTime, String timezoneOffset) {
        return localDateTime.atOffset(ZoneOffset.of(timezoneOffset)).toInstant().toString();
    }

    // Paginates defensively (a single day/month is expected to be well within the page cap, but
    // this won't silently truncate a larger one without at least a log warning), capped to avoid
    // ever looping forever against an unexpected response shape.
    private List<ImmichAssetDetailDTO> searchMetadata(ImmichCredentials creds, String takenAfter, String takenBefore, String rangeDescription) {
        List<ImmichAssetDetailDTO> allAssets = new ArrayList<>();
        // page is a required number field on Immich's side (not nullable, not a string) - always
        // start at 1 rather than sending null on the first request.
        Integer page = 1;
        int guardPages = 0;
        do {
            final Integer currentPage = page;
            ImmichSearchMetadataResponseDTO response = throttled(() -> restClient.post()
                    .uri(uri(creds, "/search/metadata", b -> b))
                    .header("x-api-key", creds.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SearchMetadataRequest(takenAfter, takenBefore, currentPage, SEARCH_PAGE_SIZE))
                    .retrieve()
                    .body(ImmichSearchMetadataResponseDTO.class));
            if (response == null || response.assets() == null) break;
            if (response.assets().items() != null) allAssets.addAll(response.assets().items());
            // The response's own nextPage still comes back as a string (or null when done) -
            // parsed back into a number for the next request's page field.
            page = parsePageNumber(response.assets().nextPage());
            guardPages++;
        } while (page != null && guardPages < MAX_SEARCH_PAGES);
        if (guardPages >= MAX_SEARCH_PAGES) {
            log.warn("Hit the {}-page pagination guard while listing Immich assets for {} - result may be undercounted", MAX_SEARCH_PAGES, rangeDescription);
        }
        return allAssets;
    }

    private static Integer parsePageNumber(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Immich returned a non-numeric nextPage value '{}', stopping pagination", raw);
            return null;
        }
    }

    public ImmichAssetDetailDTO getAssetDetail(ImmichCredentials creds, String assetId) {
        return throttled(() -> restClient.get()
                .uri(uri(creds, "/assets/{id}", b -> b, assetId))
                .header("x-api-key", creds.apiKey())
                .retrieve()
                .body(ImmichAssetDetailDTO.class));
    }

    // Streams the response body straight to a caller-chosen, deterministic path (immich-temp/
    // {photoId}.{ext} - see ImmichImportService.processSyncFromImmichTask) rather than buffering
    // in memory - originals can be large videos (iphoto's own multipart limit is 2GB, see
    // application.yml), and up to MAX_CONCURRENT_CALLS of these can be in flight at once.
    // REPLACE_EXISTING deliberately: a retry of the SYNC_FROM_IMMICH task that staged this asset
    // simply re-downloads over whatever partial/stale file a previous failed attempt left behind
    // at this same deterministic path, rather than erroring on it.
    public void downloadOriginalTo(ImmichCredentials creds, String assetId, Path targetPath) {
        throttled(() -> {
            try {
                Files.createDirectories(targetPath.getParent());
                restClient.get()
                        .uri(uri(creds, "/assets/{id}/original", b -> b, assetId))
                        .header("x-api-key", creds.apiKey())
                        .exchange((request, response) -> {
                            try (InputStream body = response.getBody()) {
                                Files.copy(body, targetPath, StandardCopyOption.REPLACE_EXISTING);
                            }
                            return null;
                        });
                return null;
            } catch (IOException e) {
                throw new ImmichApiException("Failed to download Immich asset " + assetId, e);
            }
        });
    }

    public List<ImmichAlbumSummaryDTO> listAlbums(ImmichCredentials creds, boolean ownedOnly) {
        return throttled(() -> {
            List<ImmichAlbumSummaryDTO> albums = restClient.get()
                    .uri(uri(creds, "/albums", b -> b.queryParamIfPresent("isOwned", ownedOnly ? java.util.Optional.of(true) : java.util.Optional.empty())))
                    .header("x-api-key", creds.apiKey())
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<ImmichAlbumSummaryDTO>>() {});
            return albums != null ? albums : List.of();
        });
    }

    // GET /albums/{id} (AlbumResponseDto) does NOT carry an "assets" property on current Immich
    // server versions - confirmed against a live server, detail.assets() always came back null,
    // which is why every Immich-imported album ended up with zero linked photos. An album's
    // assets have to be fetched the same way the day/month sync already does it for a date
    // range (see getMonthBuckets/getAssetsForMonth), just scoped by albumId instead: first list
    // the album's time buckets, then pull each bucket's actual asset ids.
    // timezoneOffset is fixed at UTC here (not threaded through from the caller like
    // getMonthBuckets/getAssetsForDay) - only used by Immich to decide which month a
    // near-midnight asset's bucket boundary falls into, and every bucket returned still gets
    // fully enumerated regardless, so the exact split doesn't affect which assets are found.
    public List<ImmichTimeBucketDTO> getAlbumTimeBuckets(ImmichCredentials creds, String albumId) {
        return throttled(() -> {
            List<ImmichTimeBucketDTO> buckets = restClient.get()
                    .uri(uri(creds, "/timeline/buckets", b -> b.queryParam("albumId", albumId).queryParam("size", "MONTH")))
                    .header("x-api-key", creds.apiKey())
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<ImmichTimeBucketDTO>>() {});
            return buckets != null ? buckets : List.of();
        });
    }

    // GET /timeline/bucket (singular - distinct endpoint from /timeline/buckets above) returns
    // the actual asset ids belonging to one bucket, in Immich's columnar response shape - see
    // ImmichTimeBucketAssetsDTO.
    public List<String> getAlbumBucketAssetIds(ImmichCredentials creds, String albumId, String timeBucket) {
        return throttled(() -> {
            ImmichTimeBucketAssetsDTO response = restClient.get()
                    .uri(uri(creds, "/timeline/bucket", b -> b.queryParam("albumId", albumId).queryParam("timeBucket", timeBucket)))
                    .header("x-api-key", creds.apiKey())
                    .retrieve()
                    .body(ImmichTimeBucketAssetsDTO.class);
            return response != null && response.id() != null ? response.id() : List.of();
        });
    }

    public List<ImmichSharedLinkDTO> listSharedLinks(ImmichCredentials creds) {
        return throttled(() -> {
            List<ImmichSharedLinkDTO> links = restClient.get()
                    .uri(uri(creds, "/shared-links", b -> b))
                    .header("x-api-key", creds.apiKey())
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<ImmichSharedLinkDTO>>() {});
            return links != null ? links : List.of();
        });
    }

    private <T> T throttled(Supplier<T> call) {
        try {
            throttle.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ImmichApiException("Interrupted while waiting to call Immich", e);
        }
        try {
            return call.get();
        } catch (org.springframework.web.client.RestClientException e) {
            throw new ImmichApiException("Immich API call failed: " + e.getMessage(), e);
        } finally {
            throttle.release();
        }
    }

    // Builds an absolute URI against this user's own Immich server for one request - there's no
    // shared base-url on the RestClient any more (see class comment), each user's baseUrl can
    // differ.
    private URI uri(ImmichCredentials creds, String path, java.util.function.UnaryOperator<UriComponentsBuilder> customizer, Object... pathVars) {
        String apiBase = normalizeApiBase(creds.baseUrl());
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(apiBase + path);
        return customizer.apply(builder).buildAndExpand(pathVars).toUri();
    }

    private static String normalizeApiBase(String baseUrl) {
        String trimmed = baseUrl.replaceAll("/+$", "");
        return trimmed.endsWith("/api") ? trimmed : trimmed + "/api";
    }

    // POST /search/metadata request body. BEST-EFFORT SHAPE - verify live.
    private record SearchMetadataRequest(String takenAfter, String takenBefore, Integer page, Integer size) {
    }
}
