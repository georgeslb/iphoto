package org.iphoto.mobile.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.iphoto.mobile.AppContainer

private const val TAG = "SyncWorker"

/**
 * Runs one sync pass: computes the diff fresh (so it always acts on the current state, even if
 * the worker was queued a while ago), then uploads/removes/restores accordingly. There is no
 * manual "Sync now" any more (a one-off tap could push an unbounded number of assets in a single
 * burst) - sync is driven by two triggers instead, both gated on SyncPrefs.autoSyncEnabled:
 *
 * 1. A content-URI trigger (see scheduleContentTrigger, CONTENT_TRIGGER_WORK_NAME) armed against
 *    the synced folder's tree URI - the system's own JobScheduler watches it and wakes this app
 *    the moment a file changes there, even if the process isn't running at all. This is inherently
 *    one-shot (WorkManager/JobScheduler has no periodic variant of a content trigger), so doWork()
 *    re-arms it again at the end of every run, success or failure. Reliability depends on the
 *    specific SAF DocumentsProvider backing the chosen folder actually calling notifyChange() on
 *    it - the built-in local-storage/SD-card provider does; a third-party (e.g. cloud-storage) SAF
 *    provider might never fire it, which is exactly what (2) is a fallback for.
 * 2. A plain periodic poll (see schedulePeriodic, PERIODIC_WORK_NAME) every ~15 minutes
 *    (WorkManager's own minimum periodic interval - there's no way to go lower than that).
 *
 * Uploads within one run are also capped to a byte budget (see SyncRepository.selectUploadBatch)
 * rather than draining the whole backlog at once, so a large backlog is spread across several runs.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val container: AppContainer
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val spaceId = container.syncPrefs.spaceId
        val folderUriString = container.syncPrefs.folderUri
        if (spaceId.isNullOrBlank() || folderUriString.isNullOrBlank()) {
            return Result.failure()
        }

        val result = runSync(spaceId, folderUriString)

        // Content-uri triggers are one-shot - without re-arming here, only the very first file
        // change after enabling auto-sync would ever wake the app again. Re-armed regardless of
        // which trigger caused *this* run, and regardless of success/failure, as long as
        // auto-sync is still on and a folder is still configured (it could have been turned off
        // or changed by the time this particular run finishes).
        if (container.syncPrefs.autoSyncEnabled) {
            container.syncPrefs.folderUri?.let { currentFolderUri ->
                scheduleContentTrigger(applicationContext, Uri.parse(currentFolderUri))
            }
        }

        return result
    }

    private suspend fun runSync(spaceId: String, folderUriString: String): Result {
        return try {
            val repository = container.syncRepository()
            updateSyncNotification(createForegroundInfo("Checking for changes…", 0, 0))
            val skipHashes = container.syncPrefs.excludedHashes + container.syncPrefs.movedToMainHashes
            val diff = repository.computeDiff(spaceId, Uri.parse(folderUriString), skipHashes)
            val uploadBatch = repository.selectUploadBatch(diff.toUpload)
            val total = uploadBatch.size + diff.toRemove.size + diff.toRestore.size + diff.toLocalRestore.size
            var done = 0
            Log.d(
                TAG,
                "Sync diff for space $spaceId: ${diff.toUpload.size} pending upload(s) " +
                    "(${uploadBatch.size} this run, size-capped), ${diff.toRemove.size} to remove, " +
                    "${diff.toRestore.size} to restore, ${diff.toLocalRestore.size} to restore locally"
            )

            if (uploadBatch.isNotEmpty()) {
                // Uploads run concurrently across assets (bounded by MAX_CONCURRENT_UPLOADS), not
                // within one asset's own chunks - the chunked-upload protocol tracks chunkIndex
                // explicitly, but nothing here confirms the backend can reassemble out-of-order
                // arrivals safely, so each asset's own chunk sequence stays strictly sequential.
                // coroutineScope + async/awaitAll gives the same "one failure aborts the whole
                // sync" behavior the old sequential loop had: an exception in any child cancels
                // the siblings still in flight and rethrows here, caught by doWork()'s own catch.
                Log.d(
                    TAG,
                    "Uploading ${uploadBatch.size} asset(s), up to $MAX_CONCURRENT_UPLOADS at a time"
                )
                val uploaded = AtomicInteger(0)
                coroutineScope {
                    val semaphore = Semaphore(MAX_CONCURRENT_UPLOADS)
                    uploadBatch.map { asset ->
                        async {
                            semaphore.withPermit {
                                Log.d(TAG, "Importing asset ${asset.displayName}")
                                repository.uploadAsset(asset, spaceId)
                                val nowUploaded = uploaded.incrementAndGet()
                                Log.d(TAG, "Imported $nowUploaded / ${uploadBatch.size} photos")
                                updateSyncNotification(
                                    createForegroundInfo(
                                        "Uploading photos… ($nowUploaded/${uploadBatch.size})",
                                        done + nowUploaded,
                                        total
                                    )
                                )
                            }
                        }
                    }.awaitAll()
                }
                done += uploadBatch.size
            }

            if (diff.toRemove.isNotEmpty()) {
                Log.d(TAG, "Moving ${diff.toRemove.size} removed photo(s) to garbage")
                updateSyncNotification(createForegroundInfo("Moving removed photos to garbage…", done, total))
                repository.removeFromSpace(diff.toRemove.map { it.id })
                done += diff.toRemove.size
            }

            if (diff.toRestore.isNotEmpty()) {
                // The matching file reappeared in the synced folder (e.g. someone dragged it back
                // out of .iPhotoTrash by hand) - restore the Space photo instead of leaving it
                // garbaged, since the earlier upload loop already skipped these hashes.
                Log.d(TAG, "Restoring ${diff.toRestore.size} photo(s) whose file reappeared locally")
                updateSyncNotification(createForegroundInfo("Restoring photos found back in the folder…", done, total))
                repository.restoreInSpace(diff.toRestore.map { it.id })
                done += diff.toRestore.size
            }

            if (diff.toLocalRestore.isNotEmpty()) {
                // The Space photo is active again (e.g. restored from the web app) but its file is
                // still sitting in the local trash - move it back so the phone folder matches.
                Log.d(TAG, "Restoring ${diff.toLocalRestore.size} file(s) from the local trash")
                updateSyncNotification(createForegroundInfo("Restoring files from the local trash…", done, total))
                repository.restoreLocalAssets(Uri.parse(folderUriString), diff.toLocalRestore)
            }

            container.syncPrefs.lastSyncAt = System.currentTimeMillis()
            Log.d(
                TAG,
                "Sync finished: imported ${uploadBatch.size} (${diff.toUpload.size - uploadBatch.size} " +
                    "left for a later run), removed ${diff.toRemove.size}, restored ${diff.toRestore.size}, " +
                    "locally restored ${diff.toLocalRestore.size}"
            )
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed for space $spaceId", e)
            Result.failure()
        }
    }

    // Tracks whether this doWork() run has already become a foreground service - a fresh
    // SyncWorker instance is created per run (see SyncWorkerFactory), so this naturally resets
    // every time.
    private var isForegroundEstablished = false

    // setForeground()/Service.startForeground() is meant to be called ONCE to *establish*
    // foreground status, not repeatedly for every progress tick - calling it once per uploaded
    // photo (the previous version of this code) is both non-idiomatic and, worse, multiplies how
    // many chances there are to hit Android 15+'s hard 6-hour/24h cumulative time limit on
    // dataSync-type foreground services mid-sync (ForegroundServiceStartNotAllowedException:
    // "Time limit already exhausted for foreground service type dataSync" - seen in production on
    // a large sync). That exception, like the manifest foregroundServiceType one before it, is
    // thrown asynchronously inside the OS's own service dispatch (ActivityThread.handleServiceArgs
    // on the main thread) - completely outside this method's call stack, so no try/catch here can
    // ever catch it once it happens. The only real lever available is exposure: call
    // setForeground() exactly once per run, then just update the existing notification directly
    // (a plain NotificationManager call, which doesn't go through Service.startForeground() at
    // all and can't hit this restriction) for every subsequent progress tick.
    //
    // The OS can also refuse the very first setForeground() call outright if the app has dropped
    // out of a qualifying visible state between the user tapping "Sync now" and this actually
    // running (ForegroundServiceStartNotAllowedException, seen separately in production - the app
    // was already TOP_SLEEPING by the time this ran). That specific failure mode DOES throw
    // synchronously back to the caller, which the try/catch below still guards - the notification
    // is a nice-to-have, not load-bearing for sync correctness, so a refusal degrades to "keep
    // syncing without it" rather than crashing the job.
    private suspend fun updateSyncNotification(info: ForegroundInfo) {
        if (!isForegroundEstablished) {
            try {
                setForeground(info)
                isForegroundEstablished = true
            } catch (e: Exception) {
                Log.w(TAG, "Could not promote sync to a foreground service, continuing without it", e)
            }
        } else {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, info.notification)
        }
    }

    private fun createForegroundInfo(message: String, progress: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Photo sync", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("iPhoto sync")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .apply {
                if (total > 0) setProgress(total, progress, false) else setProgress(0, 0, true)
            }
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val PERIODIC_WORK_NAME = "space_folder_sync_periodic"
        const val CONTENT_TRIGGER_WORK_NAME = "space_folder_sync_content_trigger"
        private const val CHANNEL_ID = "sync_channel"
        private const val NOTIFICATION_ID = 4201
        // WorkManager's own hard floor - PeriodicWorkRequest cannot go any lower than 15 minutes,
        // there is no configuration that gets a true periodic job running more often than this.
        // Kept as a fallback alongside the near-instant content-uri trigger below, for SAF
        // providers that don't reliably call notifyChange() on their tree.
        private const val PERIODIC_INTERVAL_MINUTES = 15L
        // Bounds how many photos upload at once - unbounded parallelism here would open one
        // chunked-upload stream per asset simultaneously, which could exhaust the OkHttp
        // connection pool / device memory on a large sync and hammer the server harder than a
        // single self-hosted instance is likely provisioned for.
        private const val MAX_CONCURRENT_UPLOADS = 3

        /** Starts (or updates the constraints of) the opt-in automatic sync - see SyncPrefs.autoSyncEnabled. */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(CONTENT_TRIGGER_WORK_NAME)
        }

        // Arms (or re-arms) a one-shot job the system wakes this app for the instant [folderUri]'s
        // contents change - even with the app process not running at all, since JobScheduler
        // itself (not any in-process listener) watches the URI. Content-uri triggers can't be
        // combined with setPeriodic()/setPersisted() (a JobScheduler restriction), and are
        // inherently one-shot - see this class's own kdoc and doWork()'s re-arm call for why every
        // run schedules the next one. ExistingWorkPolicy.REPLACE because only the latest folder
        // ever needs watching - an older pending trigger for a since-changed folder is stale.
        fun scheduleContentTrigger(context: Context, folderUri: Uri) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .addContentUriTrigger(folderUri, true)
                .build()
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(CONTENT_TRIGGER_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
