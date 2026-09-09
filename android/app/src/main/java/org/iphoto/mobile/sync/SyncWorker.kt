package org.iphoto.mobile.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import org.iphoto.mobile.AppContainer

/**
 * Runs one full Space<->folder sync: computes the diff fresh (so it always acts on the current
 * state, even if the worker was queued a while ago), then uploads/removes accordingly. Only ever
 * enqueued by a user tapping "Sync now" (see SyncViewModel.confirmSync) - there is no periodic
 * trigger anywhere in this feature, precisely so it never runs unattended and drains battery.
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
            setForeground(createForegroundInfo("Checking for changes…", 0, 0))
            val diff = repository.computeDiff(spaceId, Uri.parse(folderUriString))
            val total = diff.toUpload.size + diff.toRemove.size
            var done = 0

            for (asset in diff.toUpload) {
                setForeground(createForegroundInfo("Uploading ${asset.displayName}", done, total))
                repository.uploadAsset(asset, spaceId)
                done++
            }

            if (diff.toRemove.isNotEmpty()) {
                setForeground(createForegroundInfo("Moving removed photos to garbage…", done, total))
                repository.removeFromSpace(diff.toRemove.map { it.id })
            }

            container.syncPrefs.lastSyncAt = System.currentTimeMillis()
            Result.success()
        } catch (e: Exception) {
            Result.failure()
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
        private const val CHANNEL_ID = "sync_channel"
        private const val NOTIFICATION_ID = 4201
    }
}
