package org.iphoto.mobile.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.iphoto.mobile.IPhotoApplication
import org.iphoto.mobile.MainActivity

private const val TAG = "UploadService"
private const val CHANNEL_ID = "iphoto_upload_channel"
private const val NOTIFICATION_ID = 2001

class UploadForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialNotification = buildNotification("Préparation du téléversement...", 0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        observeUploadProgress()

        return START_NOT_STICKY
    }

    private fun observeUploadProgress() {
        job?.cancel()
        val app = application as? IPhotoApplication ?: return
        val uploadManager = app.container.uploadManager

        job = scope.launch {
            uploadManager.uploadQueue.collectLatest { queue ->
                val activeItems = queue.filter {
                    it.status == UploadStatus.UPLOADING ||
                    it.status == UploadStatus.PROCESSING ||
                    it.status == UploadStatus.QUEUED
                }

                if (activeItems.isEmpty()) {
                    val finishedCount = queue.count { it.status == UploadStatus.COMPLETED || it.status == UploadStatus.IGNORED }
                    val failedCount = queue.count { it.status == UploadStatus.FAILED }
                    if (finishedCount > 0 || failedCount > 0) {
                        val summary = when {
                            failedCount > 0 -> "Téléversement terminé avec $failedCount échec(s)"
                            finishedCount > 0 -> "$finishedCount média(s) téléversé(s) avec succès"
                            else -> "Téléversement terminé"
                        }
                        val notification = buildNotification(summary, 100, 100, ongoing = false)
                        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.notify(NOTIFICATION_ID, notification)
                    }

                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val total = queue.size
                    val completed = queue.count { it.status == UploadStatus.COMPLETED || it.status == UploadStatus.IGNORED }
                    val currentUploading = queue.filter { it.status == UploadStatus.UPLOADING }
                    val avgProgress = if (currentUploading.isNotEmpty()) {
                        currentUploading.map { it.progress }.average().toFloat()
                    } else 0f
                    val overallProgress = if (total > 0) {
                        ((completed + avgProgress) / total * 100).toInt()
                    } else 0

                    val title = "Téléversement en cours ($completed / $total)"
                    val notification = buildNotification(title, 100, overallProgress, ongoing = true)
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)
                }
            }
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iphoto:upload_wakelock").apply {
                setReferenceCounted(false)
                acquire(45 * 60 * 1000L) // 45 minutes safety timeout
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wake lock", e)
        } finally {
            wakeLock = null
        }
    }

    private fun buildNotification(text: String, maxProgress: Int, progress: Int, ongoing: Boolean = true): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("iPhoto")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)

        if (maxProgress > 0) {
            builder.setProgress(maxProgress, progress, false)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Téléversements iPhoto",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progression des téléversements de médias"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        job?.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            try {
                val intent = Intent(context, UploadForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start UploadForegroundService", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, UploadForegroundService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop UploadForegroundService", e)
            }
        }
    }
}
