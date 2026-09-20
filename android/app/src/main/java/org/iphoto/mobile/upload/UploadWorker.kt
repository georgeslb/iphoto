package org.iphoto.mobile.upload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import org.iphoto.mobile.AppContainer
import org.iphoto.mobile.R
import org.iphoto.mobile.data.repository.TaskRepository
import androidx.work.OutOfQuotaPolicy
import org.iphoto.mobile.data.repository.UploadRepository

class UploadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val container: AppContainer
) : CoroutineWorker(appContext, workerParams) {

    private val uploadManager = container.uploadManager
    private val uploadRepository = UploadRepository(container.context, container.uploadApi)
    private val taskRepository = TaskRepository(container.taskApi)
    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Uploading file")
            .setTicker("Uploading file")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        return ForegroundInfo(1, notification)
    }

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_URI) ?: return Result.failure()
        val spaceId = inputData.getString(KEY_SPACE_ID) ?: return Result.failure()
        val itemId = inputData.getString("upload_item_id") ?: return Result.failure()
        val uri = Uri.parse(uriString)

        return try {
            uploadManager.updateUploadStatus(itemId, "UPLOADING")
            val task = uploadRepository.uploadFile(uri, spaceId) { progress ->
                uploadManager.updateUploadProgress(itemId, progress)
            }

            if (task == null) {
                uploadManager.updateUploadStatus(itemId, "FAILED", "No task created")
                return Result.failure()
            }

            uploadManager.updateUploadStatus(itemId, "PROCESSING")
            while (true) {
                val updatedTask = taskRepository.getTask(task.id)
                when (updatedTask.status) {
                    "COMPLETED", "IGNORED", "TRASHED" -> {
                        uploadManager.updateUploadStatus(itemId, updatedTask.status)
                        return Result.success()
                    }
                    "FAILED" -> {
                        uploadManager.updateUploadStatus(itemId, "FAILED", "Task failed on server")
                        return Result.failure()
                    }
                }
                delay(2000)
            }
        } catch (e: Exception) {
            uploadManager.updateUploadStatus(itemId, "FAILED", e.message ?: "An unknown error occurred")
            Result.failure()
        }
    }

    private fun createNotificationChannel() {
        val name = "Uploads"
        val descriptionText = "Shows ongoing uploads"
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val KEY_URI = "uri"
        const val KEY_SPACE_ID = "spaceId"
        const val CHANNEL_ID = "upload_channel"
    }
}
