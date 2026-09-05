package org.iphoto.service.immich;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.iphoto.entities.*;
import org.iphoto.exception.ImmichApiException;
import org.iphoto.exception.NoUserFoundException;
import org.iphoto.model.*;
import org.iphoto.model.immich.ImmichAssetDetailDTO;
import org.iphoto.model.immich.ImmichAssetStatisticsDTO;
import org.iphoto.model.immich.ImmichExifDTO;
import org.iphoto.model.immich.ImmichPendingUploadPayload;
import org.iphoto.model.immich.ImmichTimeBucketDTO;
import org.iphoto.properties.IPhotoProperties;
import org.iphoto.repository.*;
import org.iphoto.service.*;
import org.iphoto.service.UserService.ImmichCredentials;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

// Drives the Immich import feature: syncing the day-index table from Immich, triggering a day's
// import, the two-phase per-asset ingestion (see TaskType.SYNC_FROM_IMMICH/UPLOAD_FROM_IMMICH),
// and the coverage check. See TODO.md / specification.md for the full design and the plan this
// followed.
@Service
@RequiredArgsConstructor
@Slf4j
public class ImmichImportService {

    private final ImmichClient immichClient;
    private final ImmichImportDayRepository immichImportDayRepository;
    private final PhotoRepository photoRepository;
    private final PhotoMetadataRepository photoMetadataRepository;
    private final TaskRepository taskRepository;
    private final TaskManagerService taskManagerService;
    private final TaskMapper taskMapper;
    private final UserRepository userRepository;
    private final SpaceRepository spaceRepository;
    private final SpaceService spaceService;
    private final PhotoStoragePathResolver photoStoragePathResolver;
    private final ThumbnailService thumbnailService;
    private final IPhotoProperties iPhotoProperties;
    private final ObjectMapper objectMapper;
    private final UserService userService;
    // Reused only for its EXIF/video/filename/filesystem date-guessing (extractBestCreationDate) -
    // see resolveDateTaken's fallback below.
    private final PhotoUploadService photoUploadService;
    // Used to run persistPhotoAndMetadata in its own, genuinely independent, immediately-committed
    // transaction - see RequiresNewTransactionService for why this exists instead of just
    // @Transactional(REQUIRES_NEW) on that method (it's called from within this same class).
    private final RequiresNewTransactionService requiresNewTransactionService;
    private final DayIndexCache dayIndexCache;

    // ---------------------------------------------------------------
    // Day-index sync + coverage
    // ---------------------------------------------------------------

    // Immich's /timeline/buckets only supports MONTH-granularity buckets on current server
    // versions (see ImmichTimeBucketDTO) - there's no day-count-only endpoint any more, so real
    // per-day rows are built by fetching every asset in each month bucket and grouping by its own
    // taken-date. Heavier than the old plain day-count sync, but it's the only way left to get an
    // accurate day-by-day index.
    @Transactional
    public List<ImmichImportDayDTO> syncDayIndex(UserEntity user, String timezoneOffset) {
        ImmichCredentials creds = userService.requireImmichCredentials(user);
        List<ImmichTimeBucketDTO> monthBuckets = immichClient.getMonthBuckets(creds, timezoneOffset);
        LocalDateTime now = LocalDateTime.now();
        Map<LocalDate, Integer> dayCounts = new TreeMap<>();
        for (ImmichTimeBucketDTO bucket : monthBuckets) {
            YearMonth month = parseMonth(bucket.timeBucket());
            if (month == null) {
                log.warn("Could not parse Immich time bucket '{}' as a month, skipping", bucket.timeBucket());
                continue;
            }
            for (ImmichAssetDetailDTO asset : immichClient.getAssetsForMonth(creds, month, timezoneOffset)) {
                LocalDate day = resolveDateTaken(asset).toLocalDate();
                dayCounts.merge(day, 1, Integer::sum);
            }
        }
        for (Map.Entry<LocalDate, Integer> entry : dayCounts.entrySet()) {
            ImmichImportDayEntity row = immichImportDayRepository.findByDayAndUser(entry.getKey(), user)
                    .orElseGet(() -> ImmichImportDayEntity.builder().day(entry.getKey()).user(user).status(ImmichImportDayStatus.NOT_IMPORTED).build());
            row.setImmichAssetCount(entry.getValue());
            row.setLastSyncedAt(now);
            immichImportDayRepository.save(row);
        }
        return getAllDaysDTO(user);
    }

    public ImmichImportOverviewDTO getOverview(UserEntity user) {
        List<ImmichImportDayDTO> days = getAllDaysDTO(user);
        long bucketedTotal = days.stream().mapToLong(d -> nz(d.immichAssetCount())).sum();
        long importedTotal = days.stream().mapToLong(d -> nz(d.importedCount())).sum();
        long immichTotal = bucketedTotal;
        Optional<ImmichCredentials> creds = userService.resolveImmichCredentials(user);
        if (creds.isPresent()) {
            try {
                ImmichAssetStatisticsDTO stats = immichClient.getAssetStatistics(creds.get());
                immichTotal = stats != null && stats.total() != null ? stats.total() : bucketedTotal;
            } catch (Exception e) {
                // The coverage check is a safety net, not the main flow - a stats-endpoint hiccup
                // shouldn't take down the whole page, just fall back to what's already known.
                log.warn("Failed to fetch Immich asset statistics for the coverage check", e);
            }
        }
        long unaccounted = Math.max(0, immichTotal - bucketedTotal);
        return new ImmichImportOverviewDTO(days, new ImmichCoverageDTO(immichTotal, bucketedTotal, importedTotal, unaccounted));
    }

    public ImmichImportDayDTO getDay(UserEntity user, LocalDate day) {
        return immichImportDayRepository.findByDayAndUser(day, user).map(this::toDTO)
                .orElseThrow(() -> new NoSuchElementException("No Immich import day row for " + day));
    }

    // ---------------------------------------------------------------
    // Per-day trigger
    // ---------------------------------------------------------------

    // Re-runnable/idempotent by design: safe to call again for a day that's IN_PROGRESS,
    // COMPLETED, or PARTIAL - this is also the entire retry mechanism (no separate retry
    // endpoint), since a PARTIAL/FAILED day's failed assets are exactly what get re-queued.
    @Transactional
    public ImmichImportDayDTO triggerDayImport(LocalDate day, UserEntity user, String timezoneOffset) {
        return triggerDayImportInternal(day, user, timezoneOffset);
    }

    // At most this many days per triggerBulkDayImport call. Each day can itself queue up to
    // hundreds of per-asset tasks, and Immich calls are already throttled server-side
    // (ImmichClient's own Semaphore(8)) - a much larger batch would just queue an enormous number
    // of tasks in one request for no real benefit over clicking "Import Selected" a few times.
    private static final int MAX_BULK_IMPORT_DAYS = 30;

    // Bulk version of triggerDayImport, for the frontend's multi-select "Import Selected" action.
    // Each day runs in its own independently-committed transaction, via
    // RequiresNewTransactionService rather than just calling the @Transactional triggerDayImport
    // above from within this same class - that would be a same-class ("self-invocation") call,
    // which Spring's proxy-based @Transactional silently does NOT intercept (see
    // RequiresNewTransactionService's own comment for the general pitfall) - so one broken day's
    // exception can never roll back or block any other day in the batch, mirroring
    // ImmichAlbumImportService's per-item transaction pattern for the same reason.
    public List<ImmichImportDayDTO> triggerBulkDayImport(List<LocalDate> days, UserEntity user, String timezoneOffset) {
        if (days == null || days.isEmpty()) return List.of();
        if (days.size() > MAX_BULK_IMPORT_DAYS) {
            throw new IllegalArgumentException("Cannot import more than " + MAX_BULK_IMPORT_DAYS + " days at once (got " + days.size() + ")");
        }
        List<ImmichImportDayDTO> results = new ArrayList<>();
        for (LocalDate day : days) {
            try {
                results.add(requiresNewTransactionService.runInNewTransaction(() -> triggerDayImportInternal(day, user, timezoneOffset)));
            } catch (Exception e) {
                log.warn("Failed to trigger Immich import for day {} during bulk import", day, e);
                // Still report this day's current row state (rather than dropping it from the
                // response) so the frontend can tell the user it didn't start, instead of it just
                // silently vanishing from the results.
                immichImportDayRepository.findByDayAndUser(day, user).map(this::toDTO).ifPresent(results::add);
            }
        }
        return results;
    }

    private ImmichImportDayDTO triggerDayImportInternal(LocalDate day, UserEntity user, String timezoneOffset) {
        ImmichCredentials creds = userService.requireImmichCredentials(user);
        ImmichImportDayEntity dayRow = immichImportDayRepository.findByDayAndUser(day, user)
                .orElseGet(() -> ImmichImportDayEntity.builder().day(day).user(user).status(ImmichImportDayStatus.NOT_IMPORTED).build());
        dayRow = immichImportDayRepository.save(dayRow); // ensure an id exists before it's referenced in task payloads

        List<ImmichAssetDetailDTO> assets = immichClient.getAssetsForDay(creds, day, timezoneOffset);
        SpaceEntity space = spaceService.getDefaultSpaceForUser(user);

        int queued = 0;
        int clearedFailures = 0;
        for (ImmichAssetDetailDTO asset : assets) {
            UUID photoId;
            try {
                photoId = UUID.fromString(asset.id());
            } catch (IllegalArgumentException e) {
                log.warn("Immich asset id '{}' is not a UUID, skipping", asset.id());
                continue;
            }
            // "Fully imported" means the row exists AND its file actually made it to disk - not
            // just photoRepository.existsById. The two-phase design below can leave a row
            // committed with its original file still only staged in immich-temp if phase 2 fails
            // between its DB commit and the final file move (see UPLOAD_FROM_IMMICH) - that state
            // must still be picked up by a retry, not silently treated as done.
            if (isFullyImported(photoId)) {
                // The asset itself is genuinely done - but if its own UPLOAD_FROM_IMMICH task
                // never reached COMPLETED (see reconcileOrphanedCompletedAsset for exactly how
                // that happens), the day's counters were never credited for it and this day would
                // otherwise stay IN_PROGRESS forever despite every one of its assets being
                // actually present. Reconcile that here rather than just silently skipping.
                reconcileOrphanedCompletedAsset(photoId, dayRow.getId());
                continue;
            }

            List<TaskEntity> existing = taskRepository.findByPhotoIdAndTaskTypeIn(photoId,
                    Set.of(TaskType.SYNC_FROM_IMMICH, TaskType.UPLOAD_FROM_IMMICH));

            Optional<TaskEntity> reclaimableUpload = existing.stream()
                    .filter(t -> t.getTaskType() == TaskType.UPLOAD_FROM_IMMICH && isReclaimable(t))
                    .findFirst();
            if (reclaimableUpload.isPresent()) {
                // Phase 1 already succeeded and staged the file in immich-temp - only phase 2 (the
                // local DB write + final move) failed or got orphaned (see isReclaimable), so
                // retry that alone by re-queuing its own payload verbatim (staged file path + all
                // metadata already known), rather than re-hitting Immich for something that was
                // never the problem.
                String payload = reclaimableUpload.get().getPayload();
                taskRepository.delete(reclaimableUpload.get());
                clearedFailures++;
                taskManagerService.createTaskEntity(user, TaskType.UPLOAD_FROM_IMMICH, photoId, payload, LocalDateTime.now().plusMinutes(1));
                queued++;
                continue;
            }

            boolean stillGenuinelyInFlight = existing.stream().anyMatch(t -> !isReclaimable(t));
            if (stillGenuinelyInFlight) continue;

            // Everything left in `existing` at this point is reclaimable (FAILED, or orphaned
            // mid-PROCESSING - see isReclaimable) - clear it and fall through to queue a brand new
            // SYNC_FROM_IMMICH below, a full restart from step 1 for this asset. Safe even for an
            // orphaned SYNC_FROM_IMMICH that might have actually finished staging its file before
            // being orphaned - SYNC_FROM_IMMICH re-downloads deterministically to the same
            // immich-temp path (see its own comment), so nothing is lost, just possibly-redundant
            // re-download work.
            if (!existing.isEmpty()) {
                taskRepository.deleteAll(existing);
                clearedFailures += existing.size();
            }

            try {
                Map<String, String> payload = new HashMap<>();
                payload.put("immichAssetId", asset.id());
                payload.put("dayRowId", dayRow.getId().toString());
                payload.put("userId", user.getId().toString());
                payload.put("spaceId", space.getId().toString());
                taskManagerService.createTaskEntity(user, TaskType.SYNC_FROM_IMMICH, photoId, objectMapper.writeValueAsString(payload), null);
                queued++;
            } catch (Exception e) {
                throw new ImmichApiException("Failed to queue import task for Immich asset " + asset.id(), e);
            }
        }

        dayRow.setImmichAssetCount(assets.size());
        // queuedCount is the day's total known size (stable across re-runs), not just this run's
        // batch - imported/skipped/failed counters are cumulative across runs, so they must be
        // compared against the same stable denominator for "is this day done" to mean anything.
        dayRow.setQueuedCount(assets.size());
        dayRow.setFailedCount(Math.max(0, nz(dayRow.getFailedCount()) - clearedFailures));
        dayRow.setLastAttemptedAt(LocalDateTime.now());
        dayRow.setStatus(queued > 0 ? ImmichImportDayStatus.IN_PROGRESS : statusForNoNewWork(dayRow));
        return toDTO(immichImportDayRepository.save(dayRow));
    }

    // How long a SYNC_FROM_IMMICH/UPLOAD_FROM_IMMICH task can sit QUEUED or PROCESSING before
    // triggerDayImportInternal treats it as orphaned rather than genuinely in flight. Needed
    // because TaskWorkerManager.pollPendingTasks only ever picks up PENDING tasks - if the app
    // restarts (or a virtual thread otherwise dies) while a task is QUEUED/PROCESSING, nothing
    // will ever pick it up again, and its day would stay stuck at IN_PROGRESS forever with no way
    // to unstick it short of manually editing the database. 15 minutes comfortably covers even a
    // slow face detection run or a large video download; a task genuinely still running that long
    // would be unusual enough that restarting it from scratch is the right call anyway.
    private static final Duration STUCK_TASK_THRESHOLD = Duration.ofMinutes(15);

    private boolean isReclaimable(TaskEntity task) {
        if (task.getStatus() == TaskStatus.FAILED) return true;
        return (task.getStatus() == TaskStatus.PROCESSING || task.getStatus() == TaskStatus.QUEUED)
                && task.getUpdatedAt() != null
                && task.getUpdatedAt().isBefore(LocalDateTime.now().minus(STUCK_TASK_THRESHOLD));
    }

    private ImmichImportDayStatus statusForNoNewWork(ImmichImportDayEntity dayRow) {
        int done = nz(dayRow.getImportedCount()) + nz(dayRow.getSkippedCount()) + nz(dayRow.getFailedCount());
        int total = nz(dayRow.getQueuedCount());
        if (total == 0) return ImmichImportDayStatus.NOT_IMPORTED;
        if (done < total) return ImmichImportDayStatus.IN_PROGRESS; // tasks from a prior trigger still running
        return nz(dayRow.getFailedCount()) == 0 ? ImmichImportDayStatus.COMPLETED : ImmichImportDayStatus.PARTIAL;
    }

    // ---------------------------------------------------------------
    // Phase 1: SYNC_FROM_IMMICH - fetch + stage, never touches photos/photo_metadata
    // ---------------------------------------------------------------

    // Returns an outcome only when the asset is fully handled without needing phase 2 (already
    // imported, or a content-duplicate of an existing photo) - TaskWorkerManager records that
    // outcome against the day row immediately in that case. An empty Optional means the file was
    // staged successfully and an UPLOAD_FROM_IMMICH task was queued to finish the job ~1 minute
    // later - the day row's outcome is recorded once *that* task finishes, not this one.
    public Optional<ImmichAssetImportOutcome> processSyncFromImmichTask(TaskEntity task) throws Exception {
        Map<String, String> payload = objectMapper.readValue(task.getPayload(), new TypeReference<>() {
        });
        String immichAssetId = payload.get("immichAssetId");
        UUID photoId = UUID.fromString(immichAssetId);
        log.info("SYNC_FROM_IMMICH task {} starting for Immich asset {}", task.getId(), immichAssetId);

        if (photoRepository.existsById(photoId)) {
            log.info("SYNC_FROM_IMMICH task {} done: asset {} already imported, nothing to stage", task.getId(), immichAssetId);
            return Optional.of(ImmichAssetImportOutcome.SKIPPED_ALREADY_IMPORTED);
        }

        UserEntity user = userRepository.findById(UUID.fromString(payload.get("userId"))).orElseThrow(NoUserFoundException::new);
        UUID spaceId = UUID.fromString(payload.get("spaceId"));
        UUID dayRowId = UUID.fromString(payload.get("dayRowId"));
        // Resolved fresh from the user's current row rather than threaded through the task
        // payload: this task may run long after it was queued (background virtual thread, no
        // HTTP session), and re-deriving from whatever password hash is on the row *right now*
        // is exactly what keeps decryption correct even if the password changed in between (see
        // UserService#resetPassword's re-encryption step).
        ImmichCredentials creds = userService.requireImmichCredentials(user);

        ImmichAssetDetailDTO detail = immichClient.getAssetDetail(creds, immichAssetId);
        String extension = FilenameUtils.getExtension(detail.originalFileName());
        Path immichTempDir = Paths.get(iPhotoProperties.getStorage().getImmichTemp());
        // Deterministic, not nanoTime-suffixed: a retry of this exact task (same photoId) safely
        // re-downloads over its own previous attempt's file rather than accumulating orphans in
        // immich-temp, and a later UPLOAD_FROM_IMMICH retry can always find it again by photoId.
        Path stagedPath = immichTempDir.resolve(photoId + "." + (StringUtils.hasText(extension) ? extension : "bin"));
        immichClient.downloadOriginalTo(creds, immichAssetId, stagedPath);

        String hash;
        try (FileInputStream fis = new FileInputStream(stagedPath.toFile())) {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(fis.readAllBytes()));
        }

        if (photoRepository.findByFileHash(hash).isPresent()) {
            Files.deleteIfExists(stagedPath);
            log.info("SYNC_FROM_IMMICH task {} done: asset {} is a content-duplicate of an existing photo, skipped", task.getId(), immichAssetId);
            return Optional.of(ImmichAssetImportOutcome.SKIPPED_DUPLICATE_CONTENT);
        }

        String contentType = StringUtils.hasText(detail.originalMimeType())
                ? detail.originalMimeType()
                : guessContentType(detail.originalFileName());
        LocalDateTime dateTaken = resolveDateTaken(detail, stagedPath.toFile());
        ImmichExifDTO exif = detail.exifInfo();

        ImmichPendingUploadPayload uploadPayload = new ImmichPendingUploadPayload(
                photoId, dayRowId, user.getId(), spaceId,
                stagedPath.toString(), detail.originalFileName(), contentType, hash, dateTaken, extension,
                exif != null && exif.fNumber() != null ? String.valueOf(exif.fNumber()) : null,
                exif != null ? exif.exposureTime() : null,
                exif != null && exif.iso() != null ? String.valueOf(exif.iso()) : null,
                exif != null && exif.focalLength() != null ? String.valueOf(exif.focalLength()) : null,
                exif != null ? exif.lensModel() : null,
                exif != null ? exif.latitude() : null,
                exif != null ? exif.longitude() : null,
                objectMapper.writeValueAsString(detail));

        taskManagerService.createTaskEntity(user, TaskType.UPLOAD_FROM_IMMICH, photoId,
                objectMapper.writeValueAsString(uploadPayload), LocalDateTime.now().plusMinutes(1));

        log.info("SYNC_FROM_IMMICH task {} done: staged asset {} at {}, queued UPLOAD_FROM_IMMICH for photo {}",
                task.getId(), immichAssetId, stagedPath, photoId);
        return Optional.empty();
    }

    // ---------------------------------------------------------------
    // Phase 2: UPLOAD_FROM_IMMICH - commit the DB rows, then (only then) move the staged file
    // ---------------------------------------------------------------

    public ImmichAssetImportOutcome finalizeUploadFromImmich(TaskEntity task) throws Exception {
        ImmichPendingUploadPayload payload = objectMapper.readValue(task.getPayload(), ImmichPendingUploadPayload.class);
        UUID photoId = payload.photoId();
        Path stagedPath = Paths.get(payload.immichTempPath());
        log.info("UPLOAD_FROM_IMMICH task {} starting for photo {}", task.getId(), photoId);

        Optional<PhotoEntity> existingPhoto = photoRepository.findById(photoId);
        if (existingPhoto.isPresent()) {
            // The row is already committed - either a genuine race (shouldn't normally happen,
            // one task per asset) or, far more likely, this task is being retried after an
            // earlier attempt already committed persistPhotoAndMetadata's own independent
            // transaction but then failed before reaching the file move below. Either way, never
            // re-insert - just make sure the staged file actually made it to its final home.
            ImmichAssetImportOutcome outcome = resumeFileMoveIfNeeded(existingPhoto.get(), stagedPath);
            log.info("UPLOAD_FROM_IMMICH task {} done: photo {} row already existed, resumed file move if needed", task.getId(), photoId);
            return outcome;
        }

        if (!Files.exists(stagedPath)) {
            throw new ImmichApiException("Staged Immich asset file missing at " + stagedPath + " for " + photoId, null);
        }

        UserEntity user = userRepository.findById(payload.userId()).orElseThrow(NoUserFoundException::new);
        SpaceEntity space = spaceRepository.findById(payload.spaceId())
                .orElseThrow(() -> new NoSuchElementException("Space not found for Immich import: " + payload.spaceId()));

        Path finalPath = photoStoragePathResolver.resolveOriginalPath(photoId, payload.extension(), payload.dateTaken());
        // Same thumbnail-generation method a normal upload uses, run directly against the staged
        // file - safe to do before the DB commit below since, unlike the original asset, the
        // thumbnail isn't the resource being gated behind a successful commit.
        String thumbPath = thumbnailService.generateThumbnail(stagedPath.toString(), photoId.toString(), payload.contentType(), payload.dateTaken());
        long fileSize = Files.size(stagedPath);

        // The whole point of the two-phase design: the asset file must only be copied into its
        // final folder once the photos/photo_metadata rows are *durably committed* - not merely
        // "no exception thrown yet". persistPhotoAndMetadata runs in its own independent,
        // immediately-committed transaction for exactly that reason (see its own comment); once
        // this call returns normally, the rows are guaranteed committed, so it's now safe to move
        // the file. If it throws instead, nothing below runs and the staged file is left exactly
        // where it was for the next retry to pick up - never moved, so never at risk of blocking
        // a future retry the way moving-then-inserting-later would (Files.move below has no
        // REPLACE_EXISTING precisely because a real collision there would mean something odd
        // happened - two tasks somehow finalizing the same asset - and should be surfaced, not
        // silently overwritten).
        persistPhotoAndMetadata(payload, user.getId(), space.getId(), finalPath, thumbPath, fileSize);

        Files.move(stagedPath, finalPath);
        log.info("UPLOAD_FROM_IMMICH task {} done: photo {} committed and moved to {}", task.getId(), photoId, finalPath);
        return ImmichAssetImportOutcome.IMPORTED;
    }

    // Deliberately its own independently-committed transaction - see RequiresNewTransactionService
    // for why (this method is called from within this same class, where Spring's proxy-based
    // @Transactional(REQUIRES_NEW) would silently be a no-op). Takes plain ids, not entity
    // instances, and re-fetches its own `user`/`space` inside the new transaction - reusing
    // entities loaded by finalizeUploadFromImmich's outer (here, suspended) transaction would hand
    // this transaction "foreign" instances belonging to a different persistence context, which
    // photoRepository.save(photo) below (photo references both) can't safely persist relationships
    // against.
    private void persistPhotoAndMetadata(ImmichPendingUploadPayload payload, UUID userId, UUID spaceId,
                                          Path finalPath, String thumbPath, long fileSize) {
        requiresNewTransactionService.runInNewTransaction(() -> {
            UserEntity user = userRepository.findById(userId).orElseThrow(NoUserFoundException::new);
            SpaceEntity space = spaceRepository.findById(spaceId)
                    .orElseThrow(() -> new NoSuchElementException("Space not found for Immich import: " + spaceId));

            PhotoEntity photo = PhotoEntity.builder().id(payload.photoId())
                    .fileName(payload.fileName())
                    .fileHash(payload.fileHash())
                    .contentType(payload.contentType())
                    .dateTaken(payload.dateTaken())
                    .user(user)
                    .space(space)
                    .originalPath(finalPath.toString())
                    .thumbnailPath(thumbPath)
                    .fileSize(fileSize)
                    .build();
            photo.setNew(true);

            PhotoMetadataEntity metadataEntity = new PhotoMetadataEntity(photo);
            metadataEntity.setAperture(payload.aperture());
            metadataEntity.setShutterSpeed(payload.shutterSpeed());
            metadataEntity.setIso(payload.iso());
            metadataEntity.setFocalLength(payload.focalLength());
            metadataEntity.setLensModel(payload.lensModel());
            metadataEntity.setLatitude(payload.latitude());
            metadataEntity.setLongitude(payload.longitude());
            if (payload.latitude() != null && payload.longitude() != null) {
                photo.setLocation(payload.latitude() + ", " + payload.longitude());
            }
            metadataEntity.setRawMetadataJson(payload.rawMetadataJson());

            // Atomic - see SpacePhotoCountListener - no lock/refresh needed to avoid a stale
            // in-memory count here anymore.
            spaceService.increaseSpacePhotoCount(space);

            photoRepository.save(photo);
            photoMetadataRepository.save(metadataEntity);
            dayIndexCache.evictSpace(spaceId);
            // Hooks the imported photo into the same async hash/duplicate-check/face-detection
            // pipeline any normally-uploaded photo goes through - see TaskMapper.
            taskMapper.updateTasksToAction(TaskMapper.Action.ON_CREATE_PHOTO_ACTION, photo.getId());
        });
    }

    // The row exists but may or may not have its file yet (see finalizeUploadFromImmich) -
    // resume by finishing the move if needed, otherwise this is a genuine no-op.
    private ImmichAssetImportOutcome resumeFileMoveIfNeeded(PhotoEntity photo, Path stagedPath) throws IOException {
        if (StringUtils.hasText(photo.getOriginalPath())) {
            Path finalPath = Paths.get(photo.getOriginalPath());
            if (!Files.exists(finalPath) && Files.exists(stagedPath)) {
                Files.move(stagedPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return ImmichAssetImportOutcome.IMPORTED;
    }

    // ---------------------------------------------------------------
    // Day-row outcome recording (called from TaskWorkerManager)
    // ---------------------------------------------------------------

    // Deliberately NOT REQUIRES_NEW (as this used to be): TaskWorkerManager.processTask calls this
    // then immediately does task.setStatus(COMPLETED)/taskRepository.save(task) in the SAME
    // transactionTemplate block. A REQUIRES_NEW here would suspend that block and commit the
    // counter increment on its own, independently and immediately - well before the task's own
    // COMPLETED status commits moments later. A crash/restart landing in that exact window left
    // the counter already incremented but the task still showing its old (non-COMPLETED) status;
    // reconcileOrphanedCompletedAsset (below) later finds that same task looking "never recorded"
    // and increments AGAIN, double-counting one real import as two. Plain @Transactional here joins
    // the caller's ambient transaction instead, so the counter update and the task's COMPLETED
    // status commit atomically together - either both survive a crash or neither does, closing the
    // window entirely. (The atomic UPDATE ... SET x = x+1 queries this calls into stay safe under
    // concurrent tasks either way - that's a separate concern from transaction propagation.)
    @Transactional
    public void recordAssetTaskOutcome(TaskEntity task, ImmichAssetImportOutcome outcome) {
        UUID dayRowId = parseDayRowId(task);
        if (dayRowId == null) return;
        switch (outcome) {
            case IMPORTED -> immichImportDayRepository.incrementImported(dayRowId);
            case SKIPPED_ALREADY_IMPORTED, SKIPPED_DUPLICATE_CONTENT -> immichImportDayRepository.incrementSkipped(dayRowId);
        }
        finalizeDayStatusIfDone(dayRowId);
    }

    // Same reasoning as recordAssetTaskOutcome above - joins the caller's transaction rather than
    // committing independently ahead of task.setStatus(FAILED).
    @Transactional
    public void recordAssetTaskFailure(TaskEntity task) {
        UUID dayRowId = parseDayRowId(task);
        if (dayRowId == null) return;
        immichImportDayRepository.incrementFailed(dayRowId);
        finalizeDayStatusIfDone(dayRowId);
    }

    private void finalizeDayStatusIfDone(UUID dayRowId) {
        immichImportDayRepository.findById(dayRowId).ifPresent(row -> {
            int done = nz(row.getImportedCount()) + nz(row.getSkippedCount()) + nz(row.getFailedCount());
            int queued = nz(row.getQueuedCount());
            if (queued > 0 && done >= queued) {
                row.setStatus(nz(row.getFailedCount()) == 0 ? ImmichImportDayStatus.COMPLETED : ImmichImportDayStatus.PARTIAL);
                immichImportDayRepository.save(row);
            }
        });
    }

    // Reads the payload as a generic JSON tree rather than a specific type - SYNC_FROM_IMMICH's
    // payload is a flat Map<String,String>, UPLOAD_FROM_IMMICH's is an ImmichPendingUploadPayload
    // record, but both serialize to a top-level JSON object with a "dayRowId" key, so this works
    // uniformly for either task type without needing to branch on task.getTaskType() here.
    private UUID parseDayRowId(TaskEntity task) {
        try {
            JsonNode node = objectMapper.readTree(task.getPayload());
            String raw = node.path("dayRowId").asText(null);
            return StringUtils.hasText(raw) ? UUID.fromString(raw) : null;
        } catch (Exception e) {
            log.warn("Could not read dayRowId from Immich import task {} payload", task.getId(), e);
            return null;
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    // "Fully imported" = the row exists AND its original file is actually present on disk - see
    // triggerDayImport's use of this for why existsById alone isn't enough any more.
    private boolean isFullyImported(UUID photoId) {
        return photoRepository.findById(photoId)
                .map(photo -> StringUtils.hasText(photo.getOriginalPath()) && Files.exists(Paths.get(photo.getOriginalPath())))
                .orElse(false);
    }

    // An asset can end up genuinely done - photo row committed, file moved to its final path,
    // isFullyImported == true - while its own UPLOAD_FROM_IMMICH task row is still stuck at
    // PROCESSING (or, less commonly, SYNC_FROM_IMMICH left non-terminal). This happens when the
    // app is killed/restarted at exactly the wrong instant: between Files.move succeeding inside
    // finalizeUploadFromImmich and TaskWorkerManager.processTask reaching
    // task.setStatus(COMPLETED) + recordAssetTaskOutcome for that same task, a couple of lines
    // later in the same method. The two-phase design already guarantees the *photo* itself is
    // safe either way (isFullyImported already stops this method from ever touching it again) -
    // but nothing else ever notices that this leftover task's outcome was never actually recorded
    // against the day's counters, so the day would stay IN_PROGRESS forever even once every one of
    // its assets is genuinely present (this exact scenario, on a real day, is what prompted this
    // fix - 7 orphaned UPLOAD_FROM_IMMICH tasks left the day permanently stuck at 133/140 despite
    // all 140 photos and files actually existing). Idempotent: once the lingering task(s) are
    // deleted here, a later call for the same asset finds nothing left and does nothing, so this
    // never double-counts.
    private void reconcileOrphanedCompletedAsset(UUID photoId, UUID dayRowId) {
        List<TaskEntity> lingering = taskRepository.findByPhotoIdAndTaskTypeIn(photoId,
                        Set.of(TaskType.SYNC_FROM_IMMICH, TaskType.UPLOAD_FROM_IMMICH)).stream()
                .filter(t -> t.getStatus() != TaskStatus.COMPLETED)
                .toList();
        if (lingering.isEmpty()) return;
        log.info("Reconciling {} orphaned-but-actually-completed task(s) for already-imported Immich asset {}",
                lingering.size(), photoId);
        taskRepository.deleteAll(lingering);
        immichImportDayRepository.incrementImported(dayRowId);
        finalizeDayStatusIfDone(dayRowId);
    }

    private List<ImmichImportDayDTO> getAllDaysDTO(UserEntity user) {
        return immichImportDayRepository.findAllByUserOrderByDayDesc(user).stream().map(this::toDTO).toList();
    }

    private ImmichImportDayDTO toDTO(ImmichImportDayEntity e) {
        return new ImmichImportDayDTO(e.getId(), e.getDay(), e.getImmichAssetCount(), e.getQueuedCount(),
                e.getImportedCount(), e.getSkippedCount(), e.getFailedCount(), e.getStatus(),
                e.getLastAttemptedAt(), e.getLastSyncedAt());
    }

    private int nz(Integer i) {
        return i != null ? i : 0;
    }

    // Handles both a plain "yyyy-MM-dd" bucket key and a full ISO-8601 timestamp (Immich version
    // differences) by just taking the year-month portion - see ImmichTimeBucketDTO.
    private YearMonth parseMonth(String raw) {
        if (!StringUtils.hasText(raw) || raw.length() < 7) return null;
        try {
            return YearMonth.parse(raw.substring(0, 7));
        } catch (Exception e) {
            return null;
        }
    }

    // BEST-EFFORT: tries exifInfo.dateTimeOriginal first (most authoritative, mirrors
    // PhotoUploadService.extractBestCreationDate preferring EXIF), then localDateTime, then
    // fileCreatedAt - each of Immich's date strings might carry an offset/zone or be a bare
    // local timestamp depending on version/field, so every candidate is tried a few ways.
    //
    // Used by syncDayIndex, which only has search-metadata DTOs for potentially thousands of
    // assets per month and never downloads them - so this overload has no file to fall back to
    // and, failing every DTO field, just returns now(). See the other overload below for the
    // real fallback, used once an asset's file actually exists on disk.
    private LocalDateTime resolveDateTaken(ImmichAssetDetailDTO detail) {
        LocalDateTime fromDetail = resolveDateTakenFromDetail(detail);
        return fromDetail != null ? fromDetail : LocalDateTime.now();
    }

    // Same DTO-field attempts as above, but as a last resort (Immich sent none of
    // exifInfo.dateTimeOriginal/localDateTime/fileCreatedAt - seen on assets with stripped EXIF)
    // falls back to PhotoUploadService.extractBestCreationDate against the asset's own staged
    // file (EXIF/video-metadata/filename/filesystem, same guesswork a normal upload already
    // relies on) before ever giving up and stamping "now". Only usable once the asset has been
    // downloaded (processSyncFromImmichTask, after immichClient.downloadOriginalTo).
    private LocalDateTime resolveDateTaken(ImmichAssetDetailDTO detail, File stagedFile) {
        LocalDateTime fromDetail = resolveDateTakenFromDetail(detail);
        if (fromDetail != null) return fromDetail;
        LocalDateTime fromFile = photoUploadService.extractBestCreationDate(stagedFile, detail.originalFileName());
        return fromFile != null ? fromFile : LocalDateTime.now();
    }

    private LocalDateTime resolveDateTakenFromDetail(ImmichAssetDetailDTO detail) {
        if (detail.exifInfo() != null) {
            LocalDateTime parsed = parseImmichInstant(detail.exifInfo().dateTimeOriginal());
            if (parsed != null) return parsed;
        }
        LocalDateTime fromLocal = parseImmichInstant(detail.localDateTime());
        if (fromLocal != null) return fromLocal;
        LocalDateTime fromCreated = parseImmichInstant(detail.fileCreatedAt());
        if (fromCreated != null) return fromCreated;
        return null;
    }

    private LocalDateTime parseImmichInstant(String raw) {
        if (!StringUtils.hasText(raw)) return null;
        try {
            return OffsetDateTime.parse(raw).toLocalDateTime();
        } catch (Exception ignored) { }
        try {
            return LocalDateTime.parse(raw);
        } catch (Exception ignored) { }
        try {
            return Instant.parse(raw).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception ignored) { }
        return null;
    }

    private static String guessContentType(String fileName) {
        String guessed = fileName != null ? URLConnection.guessContentTypeFromName(fileName) : null;
        return guessed != null ? guessed : "application/octet-stream";
    }
}
