package org.iphoto.service.immich;

import lombok.extern.slf4j.Slf4j;
import org.iphoto.exception.ImmichApiException;
import org.iphoto.model.immich.*;
import org.iphoto.service.UserService.ImmichCredentials;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
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

// NOTE: several response shapes below are BEST-EFFORT, built from Immich's documented API rather
// than a live call against a real server - each DTO/method that's uncertain says so. Verify
// against a real server and fix up field names/paths here if a live call disagrees - this class
// is deliberately the only place that knows Immich's wire format, so a fix here is a small,
// isolated patch.
@Service
@Slf4j
public class ImmichClientImpl implements ImmichClient {

    // Bounds concurrent outbound calls to a user's Immich server: TaskWorkerManager's poller has
    // no throttle of its own, so without this a 200-asset day could fire ~200 simultaneous
    // requests. Shared across all users - fine, since imports are expected to be occasional/manual,
    // not constantly running for many users at once.
    private static final int MAX_CONCURRENT_CALLS = 8;
    private static final int MAX_SEARCH_PAGES = 40;
    private static final int SEARCH_PAGE_SIZE = 250;

    // No HTTP client library (Apache HttpClient5/Jetty/Reactor) is on the classpath, so plain
    // RestClient.create() falls back to wrapping the JDK's own java.net.http.HttpClient. That
    // client's default version preference is HTTP_2 - and per its own javadoc, even for a plain
    // http:// (non-TLS) target it still sends the first request as HTTP/1.1 with an "Upgrade: h2c"
    // header, hoping the server accepts an upgrade to HTTP/2 cleartext. Immich's backend (a Node/
    // NestJS server) doesn't handle that upgrade attempt gracefully - instead of ignoring the
    // Upgrade header like a normal HTTP/1.1 server should, it aborts the connection mid-response,
    // which the JDK client surfaces as "EOFException: EOF reached while reading" (production
    // report - curl against the exact same URL/port works fine, since curl never attempts an h2c
    // upgrade for plain HTTP; only java.net.http's opportunistic upgrade attempt triggers this).
    // Pinning the client to HTTP/1.1 removes the upgrade attempt entirely.
    private final RestClient restClient = RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build()))
            .build();
    private final Semaphore throttle = new Semaphore(MAX_CONCURRENT_CALLS);

    @Override
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

    @Override
    public ImmichAssetStatisticsDTO getAssetStatistics(ImmichCredentials creds) {
        return throttled(() -> restClient.get()
                .uri(uri(creds, "/assets/statistics", b -> b))
                .header("x-api-key", creds.apiKey())
                .retrieve()
                .body(ImmichAssetStatisticsDTO.class));
    }

    @Override
    public List<ImmichAssetDetailDTO> getAssetsForDay(ImmichCredentials creds, LocalDate day, String timezoneOffset) {
        String takenAfter = isoInstant(day.atStartOfDay(), timezoneOffset);
        String takenBefore = isoInstant(day.plusDays(1).atStartOfDay().minusNanos(1), timezoneOffset);
        return searchMetadata(creds, takenAfter, takenBefore, "day " + day);
    }

    @Override
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

    @Override
    public ImmichAssetDetailDTO getAssetDetail(ImmichCredentials creds, String assetId) {
        return throttled(() -> restClient.get()
                .uri(uri(creds, "/assets/{id}", b -> b, assetId))
                .header("x-api-key", creds.apiKey())
                .retrieve()
                .body(ImmichAssetDetailDTO.class));
    }

    @Override
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

    @Override
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

    @Override
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

    @Override
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

    @Override
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
