package org.iphoto.mobile.sync

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.iphoto.mobile.model.Photo
import org.iphoto.mobile.model.TaskDTO
import org.iphoto.mobile.network.PhotoApi
import org.iphoto.mobile.network.TaskApi
import java.io.InputStream
import java.util.UUID
import kotlin.math.ceil

/** A remote Space photo, reduced to the fields the sync diff actually needs. */
data class RemotePhotoRef(val id: String, val fileHash: String?)

data class SyncDiff(
    val toUpload: List<LocalAsset>,
    val toRemove: List<RemotePhotoRef>
)

/**
 * Drives the device-folder <-> Space mirror described in the sync feature: computes what's out of
 * sync (by content hash, not filename - see FileHasher) and applies it (chunked upload for new
 * local files, move-to-garbage for Space photos no longer present locally).
 */
class SyncRepository(
    private val context: Context,
    private val photoApi: PhotoApi,
    private val taskApi: TaskApi,
    private val hashCache: HashCache
) {
    suspend fun computeDiff(spaceId: String, folderUri: Uri): SyncDiff = withContext(Dispatchers.IO) {
        val remotePhotos = fetchAllSpacePhotos(spaceId)
        val remoteHashes = remotePhotos.mapNotNull { it.fileHash?.takeIf(String::isNotBlank) }.toSet()

        val localAssets = LocalFolderScanner.scan(context, folderUri)
        val localHashes = mutableSetOf<String>()
        val toUpload = mutableListOf<LocalAsset>()

        for (asset in localAssets) {
            val hash = hashCache.get(asset.uri.toString(), asset.size, asset.lastModified)
                ?: FileHasher.md5(context, asset.uri).also {
                    hashCache.put(asset.uri.toString(), asset.size, asset.lastModified, it)
                }
            localHashes.add(hash)
            if (hash !in remoteHashes) {
                toUpload.add(asset)
            }
        }
        hashCache.persist()

        // Photos with no recorded hash are left alone rather than treated as removable - fileHash
        // is always set by the upload/edit pipeline, so a null here means "don't know", not "gone
        // from the phone", and this mirror should never delete something on an "I'm not sure".
        val toRemove = remotePhotos
            .filter { !it.fileHash.isNullOrBlank() && it.fileHash !in localHashes }
            .map { RemotePhotoRef(it.id, it.fileHash) }

        SyncDiff(toUpload, toRemove)
    }

    private suspend fun fetchAllSpacePhotos(spaceId: String): List<Photo> {
        val all = mutableListOf<Photo>()
        var page = 0
        while (true) {
            val response = photoApi.listForSpace(spaceId, page, SPACE_PAGE_SIZE)
            all += response.content
            if (response.page.isLast) break
            page++
        }
        return all
    }

    suspend fun uploadAsset(asset: LocalAsset, spaceId: String) = withContext(Dispatchers.IO) {
        val uploadId = UUID.randomUUID().toString()
        val totalChunks = maxOf(1, ceil(asset.size.toDouble() / CHUNK_SIZE).toInt())
        val mediaType = asset.mimeType.toMediaTypeOrNull()

        var completionTask: TaskDTO? = null
        val input = context.contentResolver.openInputStream(asset.uri)
            ?: throw IllegalStateException("Cannot open ${asset.uri}")
        input.use { stream ->
            val buffer = ByteArray(CHUNK_SIZE)
            var chunkIndex = 0
            while (true) {
                // InputStream.read() may return fewer bytes than the buffer even mid-stream (it's
                // only guaranteed to block for *some* data, not fill the buffer) - reading fully
                // here keeps every chunk's size matching what totalChunks/chunkIndex assume,
                // which matters for content providers that don't hand back one big read at a time.
                val read = readFully(stream, buffer)
                if (read <= 0) break
                val chunkBody = buffer.copyOf(read).toRequestBody(mediaType)
                val part = MultipartBody.Part.createFormData("chunk", asset.displayName, chunkBody)

                val response = photoApi.uploadChunk(
                    uploadId = uploadId.toPlainRequestBody(),
                    fileName = asset.displayName.toPlainRequestBody(),
                    fileSize = asset.size.toString().toPlainRequestBody(),
                    chunkIndex = chunkIndex.toString().toPlainRequestBody(),
                    totalChunks = totalChunks.toString().toPlainRequestBody(),
                    contentType = asset.mimeType.toPlainRequestBody(),
                    spaceId = spaceId.toPlainRequestBody(),
                    chunk = part
                )
                completionTask = response.task
                chunkIndex++
            }
        }

        completionTask?.let { waitForProcessing(it.id) }
    }

    private suspend fun waitForProcessing(taskId: String) {
        val deadline = System.currentTimeMillis() + TASK_POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val task = taskApi.get(taskId)
            when (task.status) {
                "COMPLETED" -> return
                "FAILED" -> throw IllegalStateException("Server failed to process upload (task $taskId)")
                else -> delay(TASK_POLL_INTERVAL_MS)
            }
        }
        throw IllegalStateException("Timed out waiting for the server to process an upload")
    }

    suspend fun removeFromSpace(photoIds: List<String>) {
        if (photoIds.isEmpty()) return
        photoApi.moveToGarbage(photoIds)
    }

    private fun readFully(stream: InputStream, buffer: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = stream.read(buffer, totalRead, buffer.size - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return totalRead
    }

    private fun String.toPlainRequestBody(): RequestBody = toRequestBody("text/plain".toMediaTypeOrNull())

    companion object {
        private const val SPACE_PAGE_SIZE = 200
        private const val CHUNK_SIZE = 1 * 1024 * 1024
        private const val TASK_POLL_INTERVAL_MS = 1000L
        private const val TASK_POLL_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
