package org.iphoto.mobile.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.iphoto.mobile.model.TaskDTO
import org.iphoto.mobile.network.UploadApi
import java.util.UUID

class UploadRepository(
    private val context: Context,
    private val uploadApi: UploadApi
) {
    @SuppressLint("Range")
    suspend fun uploadFile(uri: Uri, spaceId: String, onProgress: (Float) -> Unit): TaskDTO? {
        return withContext(Dispatchers.IO) {
            val uploadId = UUID.randomUUID().toString()

            var fileName = "unknown"
            var fileSize = -1L

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    fileName = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                    fileSize = cursor.getLong(cursor.getColumnIndex(OpenableColumns.SIZE))
                }
            }

            if (fileSize <= 0) {
                throw IllegalStateException("Could not determine file size")
            }

            val contentType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not open input stream")

            val chunkSize = 1 * 1024 * 1024 // 1MB
            val totalChunks = (fileSize + chunkSize - 1) / chunkSize
            val buffer = ByteArray(chunkSize)
            var task: TaskDTO? = null

            inputStream.use { stream ->
                for (i in 0 until totalChunks) {
                    val chunkIndex = i.toInt()
                    val bytesRead = stream.read(buffer)
                    val chunk = buffer.copyOf(bytesRead)

                    val chunkPart = MultipartBody.Part.createFormData(
                        "chunk",
                        fileName,
                        chunk.toRequestBody(contentType.toMediaTypeOrNull())
                    )

                    val response = uploadApi.uploadChunk(
                        uploadId = uploadId.toRequestBody(),
                        fileName = fileName.toRequestBody(),
                        fileSize = fileSize.toString().toRequestBody(),
                        chunkIndex = chunkIndex.toString().toRequestBody(),
                        totalChunks = totalChunks.toInt().toString().toRequestBody(),
                        contentType = contentType.toRequestBody(),
                        spaceId = spaceId.toRequestBody(),
                        chunk = chunkPart
                    )

                    if (response.status == "completed") {
                        task = response.task
                    }

                    onProgress((chunkIndex + 1).toFloat() / totalChunks)
                }
            }
            task
        }
    }
}
