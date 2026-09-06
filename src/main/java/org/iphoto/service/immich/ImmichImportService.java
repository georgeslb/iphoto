package org.iphoto.service.immich;

import org.iphoto.entities.TaskEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.ImmichAssetImportOutcome;
import org.iphoto.model.ImmichImportDayDTO;
import org.iphoto.model.ImmichImportOverviewDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// Drives the Immich import feature: syncing the day-index table from Immich, triggering a day's
// import, the two-phase per-asset ingestion (see TaskType.SYNC_FROM_IMMICH/UPLOAD_FROM_IMMICH),
// and the coverage check. See TODO.md / specification.md for the full design and the plan this
// followed.
public interface ImmichImportService {

    // Immich's /timeline/buckets only supports MONTH-granularity buckets on current server
    // versions (see ImmichTimeBucketDTO) - real per-day rows are built by fetching every asset in
    // each month bucket and grouping by its own taken-date.
    List<ImmichImportDayDTO> syncDayIndex(UserEntity user, String timezoneOffset);

    ImmichImportOverviewDTO getOverview(UserEntity user);

    ImmichImportDayDTO getDay(UserEntity user, LocalDate day);

    // Re-runnable/idempotent by design: safe to call again for a day that's IN_PROGRESS,
    // COMPLETED, or PARTIAL - this is also the entire retry mechanism (no separate retry
    // endpoint), since a PARTIAL/FAILED day's failed assets are exactly what get re-queued.
    ImmichImportDayDTO triggerDayImport(LocalDate day, UserEntity user, String timezoneOffset);

    // Bulk version of triggerDayImport, for the frontend's multi-select "Import Selected" action.
    List<ImmichImportDayDTO> triggerBulkDayImport(List<LocalDate> days, UserEntity user, String timezoneOffset);

    // Returns an outcome only when the asset is fully handled without needing phase 2 (already
    // imported, or a content-duplicate of an existing photo) - TaskWorkerManager records that
    // outcome against the day row immediately in that case. An empty Optional means the file was
    // staged successfully and an UPLOAD_FROM_IMMICH task was queued to finish the job.
    Optional<ImmichAssetImportOutcome> processSyncFromImmichTask(TaskEntity task) throws Exception;

    ImmichAssetImportOutcome finalizeUploadFromImmich(TaskEntity task) throws Exception;

    // ---------------------------------------------------------------
    // Day-row outcome recording (called from TaskWorkerManager)
    // ---------------------------------------------------------------

    void recordAssetTaskOutcome(TaskEntity task, ImmichAssetImportOutcome outcome);

    void recordAssetTaskFailure(TaskEntity task);
}
