package org.iphoto.mobile.upload

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class UploadItem(
    val id: String,
    val uri: Uri,
    val fileName: String,
    val fileSize: Long,
    val spaceId: String,
    val status: String = "PENDING",
    val progress: Float = 0f,
    val error: String? = null
)

class UploadManager(private val context: Context) {
    private val _uploadQueue = MutableStateFlow<List<UploadItem>>(emptyList())
    val uploadQueue = _uploadQueue.asStateFlow()

    private val _isDismissed = MutableStateFlow(false)
    val isDismissed = _isDismissed.asStateFlow()

    private val workManager = WorkManager.getInstance(context)

    @SuppressLint("Range")
    private fun getFileNameAndSize(uri: Uri): Pair<String, Long> {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                val size = cursor.getLong(cursor.getColumnIndex(OpenableColumns.SIZE))
                return name to size
            }
        }
        return "unknown" to -1L
    }

    fun dismiss() {
        _isDismissed.value = true
    }

    fun show() {
        _isDismissed.value = false
    }

    fun addToQueue(uris: List<Uri>, spaceId: String) {
        _isDismissed.value = false
        val newItems = uris.map { uri ->
            val (fileName, fileSize) = getFileNameAndSize(uri)
            UploadItem(
                id = UUID.randomUUID().toString(),
                uri = uri,
                fileName = fileName,
                fileSize = fileSize,
                spaceId = spaceId
            )
        }
        _uploadQueue.value = _uploadQueue.value + newItems

        newItems.forEach { item ->
            val data = Data.Builder()
                .putString(UploadWorker.KEY_URI, item.uri.toString())
                .putString(UploadWorker.KEY_SPACE_ID, item.spaceId)
                .putString("upload_item_id", item.id)
                .build()

            val uploadWorkRequest = OneTimeWorkRequest.Builder(UploadWorker::class.java)
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            workManager.enqueue(uploadWorkRequest)
        }
    }

    fun updateUploadProgress(id: String, progress: Float) {
        _uploadQueue.value = _uploadQueue.value.map {
            if (it.id == id) it.copy(progress = progress) else it
        }
    }

    fun updateUploadStatus(id: String, status: String, error: String? = null) {
        _uploadQueue.value = _uploadQueue.value.map {
            if (it.id == id) it.copy(status = status, error = error) else it
        }
    }

    fun removeUpload(id: String) {
        _uploadQueue.value = _uploadQueue.value.filterNot { it.id == id }
    }
}
