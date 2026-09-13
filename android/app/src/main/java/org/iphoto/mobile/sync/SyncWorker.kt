package org.iphoto.mobile.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import org.iphoto.mobile.AppContainer

private const val TAG = "SyncWorker"

/**
 * Runs one full Space<->folder sync: computes the diff fresh (so it always acts on the current
 * state, even if the worker was queued a while ago), then uploads/removes/restores accordingly.
 * Enqueued either as a one-off by a user tapping "Sync now" (see SyncViewModel.confirmSync,
 * UNIQUE_WORK_NAME) or periodically once the user opts into automatic sync (see schedulePeriodic
 * below, PERIODIC_WORK_NAME, gated by SyncPrefs.autoSyncEnabled) - the two run under different
 * unique-work names so an automatic run in flight never blocks a manual "Sync now" tap, or the
 * other way around.
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

        return try {
            val repository = container.syncRepository()
            promoteToForeground(createForegroundInfo("Checking for changes…", 0, 0))
            val skipHashes = container.syncPrefs.excludedHashes + container.syncPrefs.movedToMainHashes
            val diff = repository.computeDiff(spaceId, Uri.parse(folderUriString), skipHashes)
            val total = diff.toUpload.size + diff.toRemove.size + diff.toRestore.size + diff.toLocalRestore.size
            var done = 0
            Log.d(
                TAG,
                "Sync diff for space $spaceId: ${diff.toUpload.size} to upload, ${diff.toRemove.size} to " +
                    "remove, ${diff.toRestore.size} to restore, ${diff.toLocalRestore.size} to restore locally"
            )

            for (asset in diff.toUpload) {
                Log.d(TAG, "Importing asset ${asset.displayName} (${done + 1}/${diff.toUpload.size})")
                promoteToForeground(createForegroundInfo("Uploading ${asset.displayName}", done, total))
                repository.uploadAsset(asset, spaceId)
                done++
                Log.d(TAG, "Imported $done / ${diff.toUpload.size} photos")
            }

            if (diff.toRemove.isNotEmpty()) {
                Log.d(TAG, "Moving ${diff.toRemove.size} removed photo(s) to garbage")
                promoteToForeground(createForegroundInfo("Moving removed photos to garbage…", done, total))
                repository.removeFromSpace(diff.toRemove.map { it.id })
                done += diff.toRemove.size
            }

            if (diff.toRestore.isNotEmpty()) {
                // The matching file reappeared in the synced folder (e.g. someone dragged it back
                // out of .iPhotoTrash by hand) - restore the Space photo instead of leaving it
                // garbaged, since the earlier upload loop already skipped these hashes.
                Log.d(TAG, "Restoring ${diff.toRestore.size} photo(s) whose file reappeared locally")
                promoteToForeground(createForegroundInfo("Restoring photos found back in the folder…", done, total))
                repository.restoreInSpace(diff.toRestore.map { it.id })
                done += diff.toRestore.size
            }

            if (diff.toLocalRestore.isNotEmpty()) {
                // The Space photo is active again (e.g. restored from the web app) but its file is
                // still sitting in the local trash - move it back so the phone folder matches.
                Log.d(TAG, "Restoring ${diff.toLocalRestore.size} file(s) from the local trash")
                promoteToForeground(createForegroundInfo("Restoring files from the local trash…", done, total))
                repository.restoreLocalAssets(Uri.parse(folderUriString), diff.toLocalRestore)
            }

            container.syncPrefs.lastSyncAt = System.currentTimeMillis()
            Log.d(
                TAG,
                "Sync finished: imported ${diff.toUpload.size}, removed ${diff.toRemove.size}, " +
                    "restored ${diff.toRestore.size}, locally restored ${diff.toLocalRestore.size}"
            )
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed for space $spaceId", e)
            Result.failure()
        }
    }

    // The OS can refuse to let a Worker promote itself to a foreground service if the app has
    // dropped out of a qualifying visible state between the user tapping "Sync now" and this
    // actually running (ForegroundServiceStartNotAllowedException, seen in production - the app
    // was already TOP_SLEEPING by the time this ran). The notification/foreground promotion is
    // only there so the OS is less likely to kill the process mid-sync - it's not load-bearing for
    // correctness, so a refusal here should degrade to "keep syncing without it", not crash the job.
    private suspend fun promoteToForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (e: Exception) {
            Log.w(TAG, "Could not promote sync to a foreground service, continuing without it", e)
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
        const val UNIQUE_WORK_NAME = "space_folder_sync"
        const val PERIODIC_WORK_NAME = "space_folder_sync_periodic"
        private const val CHANNEL_ID = "sync_channel"
        private const val NOTIFICATION_ID = 4201
        // WorkManager's own minimum periodic interval is 15 minutes, but a sync walks the whole
        // folder tree and re-hashes anything HashCache doesn't already know about - a longer
        // interval keeps automatic sync from being disruptive on its own, on top of the
        // network/battery constraints below.
        private const val PERIODIC_INTERVAL_HOURS = 6L

        /** Starts (or updates the constraints of) the opt-in automatic sync - see SyncPrefs.autoSyncEnabled. */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }
}
