package org.iphoto.mobile.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "UploadManager"

class UploadManager(
    private val context: Context,
    private val syncRepositoryProvider: () -> SyncRepository,
    private val onLibraryChanged: () -> Unit,
    private val onFolderChanged: ((Uri) -> Unit)? = null,
    private val scope: CoroutineScope
) {
    private val _uploadQueue = MutableStateFlow<List<UploadItem>>(emptyList())
    val uploadQueue: StateFlow<List<UploadItem>> = _uploadQueue.asStateFlow()

    private val queueChannel = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            processQueueLoop()
        }
    }

    fun enqueue(asset: LocalAsset, folderUri: Uri?, spaceId: String, deleteAfter: Boolean) {
        val item = UploadItem(
            asset = asset,
            folderUri = folderUri,
            spaceId = spaceId,
            deleteAfter = deleteAfter,
            status = UploadStatus.QUEUED
        )
        _uploadQueue.update { it + item }
        queueChannel.trySend(Unit)
    }

    fun enqueueAll(assetsWithFolder: List<Pair<LocalAsset, Uri?>>, spaceId: String, deleteAfter: Boolean) {
        val newItems = assetsWithFolder.map { (asset, folderUri) ->
            UploadItem(
                asset = asset,
                folderUri = folderUri,
                spaceId = spaceId,
                deleteAfter = deleteAfter,
                status = UploadStatus.QUEUED
            )
        }
        _uploadQueue.update { it + newItems }
        queueChannel.trySend(Unit)
    }

    fun retry(itemId: String) {
        _uploadQueue.update { list ->
            list.map { item ->
                if (item.id == itemId) {
                    item.copy(status = UploadStatus.QUEUED, progress = 0f, error = null)
                } else item
            }
        }
        queueChannel.trySend(Unit)
    }

    fun registerOrUpdate(item: UploadItem) {
        _uploadQueue.update { list ->
            val index = list.indexOfFirst { it.id == item.id || it.asset.uri == item.asset.uri }
            if (index >= 0) {
                list.toMutableList().apply { set(index, item) }
            } else {
                list + item
            }
        }
    }

    fun updateProgress(itemId: String, progress: Float) {
        updateItem(itemId) { it.copy(progress = progress, status = UploadStatus.UPLOADING) }
    }

    fun updateStatus(itemId: String, status: UploadStatus, taskId: String? = null, photoId: String? = null) {
        updateItem(itemId) {
            it.copy(
                status = status,
                taskId = taskId ?: it.taskId,
                photoId = photoId ?: it.photoId,
                progress = 1f
            )
        }
    }

    fun updateFailed(itemId: String, error: String) {
        updateItem(itemId) { it.copy(status = UploadStatus.FAILED, error = error) }
    }

    fun clearFinished() {
        _uploadQueue.update { list ->
            list.filter {
                it.status == UploadStatus.QUEUED ||
                it.status == UploadStatus.UPLOADING ||
                it.status == UploadStatus.PROCESSING
            }
        }
    }

    fun remove(itemId: String) {
        _uploadQueue.update { list -> list.filterNot { it.id == itemId } }
    }

    private suspend fun processQueueLoop() {
        while (true) {
            val nextItem = _uploadQueue.value.firstOrNull { it.status == UploadStatus.QUEUED }
            if (nextItem == null) {
                queueChannel.receive()
                continue
            }

            processItem(nextItem)
        }
    }

    private suspend fun processItem(item: UploadItem) {
        updateItem(item.id) { it.copy(status = UploadStatus.UPLOADING, progress = 0f, error = null) }
        val repo = syncRepositoryProvider()

        try {
            val task = repo.uploadAsset(
                asset = item.asset,
                spaceId = item.spaceId,
                onProgress = { uploaded, total ->
                    val progressFraction = uploaded.toFloat() / maxOf(1, total)
                    updateItem(item.id) { it.copy(progress = progressFraction) }
                },
                onTaskCreated = { taskId ->
                    updateItem(item.id) {
                        it.copy(
                            status = UploadStatus.PROCESSING,
                            taskId = taskId,
                            progress = 1f
                        )
                    }
                }
            )

            val status = when (task?.status) {
                "COMPLETED" -> {
                    onLibraryChanged()
                    UploadStatus.COMPLETED
                }
                "IGNORED" -> UploadStatus.IGNORED
                "TRASHED" -> UploadStatus.TRASHED
                "FAILED" -> UploadStatus.FAILED
                else -> UploadStatus.COMPLETED
            }

            updateItem(item.id) {
                it.copy(
                    status = status,
                    photoId = task?.photoId,
                    progress = 1f,
                    error = if (status == UploadStatus.FAILED) "Import failed on server" else null
                )
            }

            // If user clicked Upload & Trash: if server answered and status is NOT a FAILURE, move asset to trash
            if (item.deleteAfter && status != UploadStatus.FAILED) {
                withContext(Dispatchers.IO) {
                    var trashed = false
                    if (item.folderUri != null) {
                        try {
                            trashed = LocalTrash.moveToTrash(context, item.folderUri, item.asset)
                        } catch (e: Exception) {
                            Log.w(TAG, "LocalTrash.moveToTrash failed: ${e.message}")
                        }
                    }
                    if (!trashed && item.folderUri != null) {
                        try {
                            val root = DocumentFile.fromTreeUri(context, item.folderUri)
                            if (root != null) {
                                val sourceDir = if (item.asset.relativeDir.isEmpty()) root else LocalTrash.resolveOrCreateDir(root, item.asset.relativeDir)
                                val file = sourceDir?.findFile(item.asset.displayName)
                                if (file != null && file.exists()) {
                                    trashed = file.delete()
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "TreeDocumentFile find & delete failed: ${e.message}")
                        }
                    }
                    if (!trashed) {
                        try {
                            trashed = DocumentsContract.deleteDocument(context.contentResolver, item.asset.uri)
                        } catch (e: Exception) {
                            Log.w(TAG, "DocumentsContract.deleteDocument failed: ${e.message}")
                        }
                    }
                    item.folderUri?.let { onFolderChanged?.invoke(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for ${item.asset.displayName}", e)
            updateItem(item.id) {
                it.copy(
                    status = UploadStatus.FAILED,
                    error = e.message ?: "Upload failed"
                )
            }
        }
    }

    private fun updateItem(itemId: String, transform: (UploadItem) -> UploadItem) {
        _uploadQueue.update { list ->
            list.map { if (it.id == itemId) transform(it) else it }
        }
    }
}
