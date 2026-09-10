package org.iphoto.service.immich;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.AlbumEntity;
import org.iphoto.entities.ShareEntity;
import org.iphoto.entities.TaskEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.exception.ImmichApiException;
import org.iphoto.model.ImmichAlbumImportStatusDTO;
import org.iphoto.model.ImmichAlbumImportTriggerResultDTO;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;
import org.iphoto.model.immich.ImmichAlbumSummaryDTO;
import org.iphoto.model.immich.ImmichSharedLinkDTO;
import org.iphoto.model.immich.ImmichTimeBucketDTO;
import org.iphoto.repository.AlbumRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.ShareRepository;
import org.iphoto.repository.TaskRepository;
import org.iphoto.service.AlbumService;
import org.iphoto.service.ShareService;
import org.iphoto.service.SpaceService;
import org.iphoto.service.TaskManagerService;
import org.iphoto.service.UserService;
import org.iphoto.service.UserService.ImmichCredentials;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

// Each album and each shared link is processed in its own transaction (via TransactionTemplate,
// not a single @Transactional spanning the whole run) so one broken item can't mark the whole
// run's transaction rollback-only and silently undo everything already imported before it - see
// the per-item try/catch below, which only works correctly because each unit truly commits or
// rolls back on its own.
@Service
@RequiredArgsConstructor
@Slf4j
public class ImmichAlbumImportServiceImpl implements ImmichAlbumImportService {

    private static final String SOURCE = "IMMICH";

    // A task in any of these statuses is either not yet run or actively running - queuing another
    // one for the same album while one of these is outstanding would just race it. FAILED/
    // COMPLETED are NOT in this set: re-running "Import Albums" is meant to retry a failure or
    // pick up photos that synced since the last successful run.
    private static final Set<TaskStatus> IN_FLIGHT_STATUSES = Set.of(TaskStatus.PENDING, TaskStatus.QUEUED, TaskStatus.PROCESSING);

    private final ImmichClient immichClient;
    private final AlbumRepository albumRepository;
    private final AlbumService albumService;
    private final ShareRepository shareRepository;
    private final SpaceService spaceService;
    private final PhotoRepository photoRepository;
    private final TransactionTemplate transactionTemplate;
    private final UserService userService;
    private final TaskManagerService taskManagerService;
    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    @Override
    public ImmichAlbumImportTriggerResultDTO triggerAlbumImport(UserEntity user) {
        ImmichCredentials creds = userService.requireImmichCredentials(user);
        List<String> warnings = new ArrayList<>();
        int albumsQueued = 0, albumsAlreadyInFlight = 0;
        int sharedLinksCreated = 0, sharedLinksSkipped = 0;

        List<ImmichAlbumSummaryDTO> albums;
        try {
            albums = immichClient.listAlbums(creds, true);
        } catch (Exception e) {
            throw new ImmichApiException("Failed to list Immich albums", e);
        }

        // Tracks which Immich album id maps to which iphoto album, for the shared-link pass below.
        // Populated regardless of whether this album's photo-linking task has run yet - the local
        // AlbumEntity row always exists after queueAlbumImport, only its photos are still pending.
        Map<String, UUID> importedAlbumIds = new HashMap<>();

        for (ImmichAlbumSummaryDTO albumSummary : albums) {
            try {
                QueueResult result = transactionTemplate.execute(status -> queueAlbumImport(albumSummary, user));
                importedAlbumIds.put(albumSummary.id(), result.albumId());
                if (result.queued()) albumsQueued++; else albumsAlreadyInFlight++;
            } catch (Exception e) {
                warnings.add("Album '" + albumSummary.albumName() + "' (immich id " + albumSummary.id() + "): " + e.getMessage());
                log.warn("Failed to queue Immich album {} for import", albumSummary.id(), e);
            }
        }

        List<ImmichSharedLinkDTO> links;
        try {
            links = immichClient.listSharedLinks(creds);
        } catch (Exception e) {
            warnings.add("Failed to list Immich shared links: " + e.getMessage());
            links = List.of();
        }

        for (ImmichSharedLinkDTO link : links) {
            if (!link.isAlbumLevel()) {
                warnings.add("Shared link " + link.id() + " is asset-level - iphoto shares are album-only, skipped.");
                sharedLinksSkipped++;
                continue;
            }
            try {
                boolean created = Boolean.TRUE.equals(transactionTemplate.execute(status ->
                        processOneSharedLink(link, user, importedAlbumIds)));
                if (created) sharedLinksCreated++;
            } catch (Exception e) {
                warnings.add("Shared link " + link.id() + ": " + e.getMessage());
                sharedLinksSkipped++;
                log.warn("Failed to import Immich shared link {}", link.id(), e);
            }
        }

        return new ImmichAlbumImportTriggerResultDTO(albumsQueued, albumsAlreadyInFlight, sharedLinksCreated, sharedLinksSkipped, warnings);
    }

    private QueueResult queueAlbumImport(ImmichAlbumSummaryDTO albumSummary, UserEntity user) {
        AlbumEntity album = albumRepository.findByExternalSourceAndExternalId(SOURCE, albumSummary.id())
                .orElseGet(() -> AlbumEntity.builder()
                        .space(spaceService.getDefaultSpaceForUser(user))
                        .name(albumSummary.albumName())
                        .photoCount(0)
                        .externalSource(SOURCE)
                        .externalId(albumSummary.id())
                        .build());
        album = albumRepository.save(album);

        boolean alreadyInFlight = taskRepository.findByPhotoIdAndTaskTypeIn(album.getId(), Set.of(TaskType.IMPORT_IMMICH_ALBUM))
                .stream().anyMatch(t -> IN_FLIGHT_STATUSES.contains(t.getStatus()));
        if (alreadyInFlight) {
            return new QueueResult(album.getId(), false);
        }

        Map<String, String> payload = new HashMap<>();
        payload.put("immichAlbumId", albumSummary.id());
        payload.put("albumName", albumSummary.albumName());
        try {
            taskManagerService.createTaskEntity(user, TaskType.IMPORT_IMMICH_ALBUM, album.getId(),
                    objectMapper.writeValueAsString(payload), LocalDateTime.now());
        } catch (JsonProcessingException e) {
            throw new ImmichApiException("Failed to queue album import task for " + albumSummary.id(), e);
        }
        return new QueueResult(album.getId(), true);
    }

    @Override
    public void processAlbumImportTask(TaskEntity task) {
        Map<String, String> payload;
        try {
            payload = objectMapper.readValue(task.getPayload(), new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new ImmichApiException("Failed to read album import task payload", e);
        }
        String immichAlbumId = payload.get("immichAlbumId");
        String albumName = payload.get("albumName");
        UUID albumId = task.getPhotoId();

        UserEntity user = userService.findById(task.getUserId())
                .orElseThrow(() -> new NoSuchElementException("User not found: " + task.getUserId()));
        ImmichCredentials creds = userService.requireImmichCredentials(user);

        List<ImmichTimeBucketDTO> buckets = immichClient.getAlbumTimeBuckets(creds, immichAlbumId);
        List<String> assetIds = new ArrayList<>();
        for (ImmichTimeBucketDTO bucket : buckets) {
            assetIds.addAll(immichClient.getAlbumBucketAssetIds(creds, immichAlbumId, bucket.timeBucket()));
        }
        log.info("Immich album {} ('{}'): {} asset id(s) across {} time bucket(s)",
                immichAlbumId, albumName, assetIds.size(), buckets.size());

        List<UUID> linkable = new ArrayList<>();
        int skipped = 0;
        for (String rawId : assetIds) {
            try {
                UUID assetId = UUID.fromString(rawId);
                if (photoRepository.existsById(assetId)) {
                    linkable.add(assetId);
                } else {
                    skipped++;
                }
            } catch (IllegalArgumentException ignored) {
                log.warn("Immich album {}: asset id '{}' is not a valid UUID, skipping", immichAlbumId, rawId);
                skipped++;
            }
        }
        log.info("Immich album {} ('{}'): {} linkable locally, {} skipped (not yet imported or invalid id)",
                immichAlbumId, albumName, linkable.size(), skipped);
        if (!linkable.isEmpty()) {
            albumService.addPhotosToAlbum(albumId, linkable);
            log.info("Immich album {} ('{}'): addPhotosToAlbum completed for {} photo(s)", immichAlbumId, albumName, linkable.size());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImmichAlbumImportStatusDTO> listAlbumImportStatus(UserEntity user) {
        List<TaskEntity> tasks = taskRepository.findByUserIdAndTaskType(user.getId(), TaskType.IMPORT_IMMICH_ALBUM);
        Map<UUID, TaskEntity> latestByAlbum = new HashMap<>();
        for (TaskEntity task : tasks) {
            latestByAlbum.merge(task.getPhotoId(), task,
                    (a, b) -> a.getUpdatedAt().isAfter(b.getUpdatedAt()) ? a : b);
        }

        List<ImmichAlbumImportStatusDTO> result = new ArrayList<>();
        for (TaskEntity task : latestByAlbum.values()) {
            Map<String, String> payload;
            try {
                payload = objectMapper.readValue(task.getPayload(), new TypeReference<>() {});
            } catch (JsonProcessingException e) {
                log.warn("Skipping unreadable IMPORT_IMMICH_ALBUM task {} payload", task.getId(), e);
                continue;
            }
            Integer photoCount = albumRepository.findByIdInMainSpace(task.getPhotoId()).map(AlbumEntity::getPhotoCount).orElse(null);
            result.add(new ImmichAlbumImportStatusDTO(
                    payload.get("immichAlbumId"), payload.get("albumName"), task.getPhotoId(),
                    task.getStatus(), photoCount, task.getErrorMessage(), task.getUpdatedAt()));
        }
        result.sort(Comparator.comparing(ImmichAlbumImportStatusDTO::albumName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    // Returns true if a new ShareEntity was created (false if it already existed, or if the
    // target album genuinely can't be found).
    private Boolean processOneSharedLink(ImmichSharedLinkDTO link, UserEntity user, Map<String, UUID> importedAlbumIds) {
        if (shareRepository.findByExternalSourceAndExternalId(SOURCE, link.id()).isPresent()) {
            return false; // already imported on a previous run
        }

        UUID iphotoAlbumId = importedAlbumIds.get(link.album().id());
        if (iphotoAlbumId == null) {
            // Not in this run's "owned albums" pass (e.g. an album shared with this user) - look
            // it up directly rather than assuming it was never imported.
            iphotoAlbumId = albumRepository.findByExternalSourceAndExternalId(SOURCE, link.album().id())
                    .map(AlbumEntity::getId)
                    .orElse(null);
        }
        if (iphotoAlbumId == null) {
            throw new NoSuchElementException("points at an Immich album that hasn't been imported");
        }
        UUID resolvedAlbumId = iphotoAlbumId;

        AlbumEntity album = albumRepository.findByIdInMainSpace(resolvedAlbumId)
                .orElseThrow(() -> new NoSuchElementException("imported album " + resolvedAlbumId + " no longer exists"));
        String userMd5 = ShareService.computeUserMd5(user.getId());
        String shareName = resolveShareName(link, album, userMd5);

        ShareEntity share = ShareEntity.builder()
                .space(spaceService.getDefaultSpaceForUser(user))
                .name(shareName)
                .userMd5(userMd5)
                .album(album)
                .externalSource(SOURCE)
                .externalId(link.id())
                .build();
        shareRepository.save(share);
        return true;
    }

    // Share names double as the URL path segment (/share/{userMd5}/{name}), and must be unique
    // per space (name_space_unique_shares). Reuse Immich's own shared-link `key` verbatim as the
    // name whenever possible - it's exactly the token Immich itself used to identify this share
    // (immich-host/share/{key}), already URL-path-safe, and reusing it is the closest iphoto can
    // get to "the same share link" carrying over (the /share/{userMd5}/... prefix is unavoidable -
    // every iphoto share is addressed that way, not just Immich-imported ones). Case is preserved
    // deliberately - lowercasing/slugifying it the way sanitizeShareName does would change the
    // token and defeat the point. Only falls back to a generated slug if Immich sent no key, or if
    // (astronomically unlikely for a random token, but checked rather than trusted blindly - a
    // save-time unique-constraint violation would abort this share's whole per-item transaction)
    // the key happens to collide with a share name this user already has.
    private String resolveShareName(ImmichSharedLinkDTO link, AlbumEntity album, String userMd5) {
        if (StringUtils.hasText(link.key())) {
            if (shareRepository.findByUserMd5AndName(userMd5, link.key()).isEmpty()) {
                return link.key();
            }
            log.warn("Immich shared link key '{}' collides with an existing share name, falling back to a generated name", link.key());
        }
        return sanitizeShareName(album.getName() + "-" + link.id());
    }

    // Fallback only (see resolveShareName) - must still be unique per space
    // (name_space_unique_shares), so derive one from the album name plus a slice of the Immich
    // link id to keep collisions across differently-shared albums with the same name rare.
    private static String sanitizeShareName(String raw) {
        String sanitized = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-").replaceAll("-{2,}", "-");
        return sanitized.length() > 100 ? sanitized.substring(0, 100) : sanitized;
    }

    private record QueueResult(UUID albumId, boolean queued) {
    }
}
