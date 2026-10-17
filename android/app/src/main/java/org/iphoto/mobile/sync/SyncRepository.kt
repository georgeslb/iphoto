package org.iphoto.mobile.sync

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.iphoto.mobile.model.CheckHashesRequest
import org.iphoto.mobile.model.Photo
import org.iphoto.mobile.model.TaskDTO
import org.iphoto.mobile.network.PhotoApi
import org.iphoto.mobile.network.TaskApi
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.ceil

/** A remote Space photo, reduced to the fields the sync diff actually needs. */
data class RemotePhotoRef(val id: String, val fileHash: String?)

data class SyncDiff(
    val toUpload: List<LocalAsset>,
    val toRemove: List<RemotePhotoRef>,
    // Garbage photos in this Space whose fileHash reappeared in the synced folder - e.g. the user
    // dragged the file back out of the local trash (LocalTrash) with a file manager. Restored via
    // the API instead of being re-uploaded as a "new" photo (which the server would just bounce
    // back as TRASHED - see UploadController/PhotoServiceImpl#uploadAsset - so re-uploading would
    // just waste bandwidth, not actually undo the garbage state).
    val toRestore: List<RemotePhotoRef> = emptyList(),
    // Files sitting in the local trash whose hash matches a still-*active* remote photo - e.g. the
    // photo was restored from the web app while its file was already trashed locally. Moved back
    // to the folder root to keep the phone side consistent.
    val toLocalRestore: List<LocalAsset> = emptyList()
)

/**
 * Drives the device-folder <-> Space mirror described in the sync feature: computes what's out of
 * sync (by content hash, not filename - see FileHasher) and applies it (chunked upload for new
 * local files, move-to-garbage/restore for Space photos, mirrored on-device via LocalTrash for a
 * synced folder's own garbage/restore state).
 */
class SyncRepository(
    private val context: Context,
    private val photoApi: PhotoApi,
    private val taskApi: TaskApi,
    private val hashCache: HashCache
) {
    suspend fun computeDiff(
        spaceId: String,
        folderUri: Uri,
        // Hashes of photos that left this Space some way other than "the file disappeared from the
        // folder" - explicitly excluded from sync (SyncPrefs.excludedHashes) or moved to the Main
        // Space (SyncPrefs.movedToMainHashes), see the corresponding SpaceDetailScreen/SyncScreen
        // actions. Both garbage/relocate the photo remotely right away without touching the local
        // file, so on every later pass its hash still shows up in localHashToAsset like any other
        // present file; without this, the loop below would either re-upload it (nothing else marks
        // it as already handled) or auto-restore it (a garbage entry whose hash matches a present
        // local file is exactly what toRestore is otherwise for) - either way undoing the original
        // action within a single sync cycle. Callers pass the union of both SyncPrefs sets.
        skipHashes: Set<String> = emptySet()
    ): SyncDiff = withContext(Dispatchers.IO) {
        val remotePhotos = fetchAllSpacePhotos(spaceId)
        val remoteHashes = remotePhotos.mapNotNull { it.fileHash?.takeIf(String::isNotBlank) }.toSet()

        val remoteGarbagePhotos = fetchAllSpaceGarbagePhotos(spaceId)
        val garbageIdByHash = remoteGarbagePhotos
            .mapNotNull { photo -> photo.fileHash?.takeIf(String::isNotBlank)?.let { it to photo.id } }
            .toMap()

        val localAssets = LocalFolderScanner.scan(context, folderUri)
        val localHashToAsset = mutableMapOf<String, LocalAsset>()
        for (asset in localAssets) {
            localHashToAsset[hashOf(asset)] = asset
        }

        // Computed before toRemove below specifically so a file sitting only in the trash (not the
        // root) can be excluded from it - see toRemove's own comment for why.
        val trashAssets = LocalTrash.scan(context, folderUri)
        val trashHashToAsset = mutableMapOf<String, LocalAsset>()
        for (asset in trashAssets) {
            trashHashToAsset[hashOf(asset)] = asset
        }

        val toUpload = mutableListOf<LocalAsset>()
        val toRestore = mutableListOf<RemotePhotoRef>()
        for ((hash, asset) in localHashToAsset) {
            if (hash in skipHashes) continue
            val garbageId = garbageIdByHash[hash]
            when {
                garbageId != null -> toRestore.add(RemotePhotoRef(garbageId, hash))
                hash !in remoteHashes -> toUpload.add(asset)
            }
        }

        // Photos with no recorded hash are left alone rather than treated as removable - fileHash
        // is always set by the upload/edit pipeline, so a null here means "don't know", not "gone
        // from the phone", and this mirror should never delete something on an "I'm not sure". A
        // photo whose file is sitting in the local trash (not the root) isn't "gone from the
        // phone" either, even though it's absent from localHashToAsset - it's still tracked there
        // and about to be moved back to the root by toLocalRestore below, so garbaging it here
        // would just get undone (and the file re-trashed, then re-restored) over the next couple
        // of sync passes instead of settling in this one.
        val toRemove = remotePhotos
            .filter { !it.fileHash.isNullOrBlank() && it.fileHash !in localHashToAsset.keys && it.fileHash !in trashHashToAsset.keys }
            .map { RemotePhotoRef(it.id, it.fileHash) }

        val toLocalRestore = trashHashToAsset
            .filter { (hash, _) -> hash in remoteHashes && hash !in localHashToAsset.keys }
            .values.toList()

        hashCache.persist()

        SyncDiff(toUpload, toRemove, toRestore, toLocalRestore)
    }

    // Caps one sync run's uploads to a cumulative size budget instead of draining the whole
    // backlog in one burst - a large backlog is spread across several (now 15-minutes-apart,
    // see SyncWorker) runs instead. Always includes at least one asset even if it alone exceeds
    // the budget, so one unusually large file can't stall progress on everything behind it
    // forever. [assets] order is preserved as given (whatever order computeDiff produced it in) -
    // this doesn't try to prioritize smaller/newer/older files over each other.
    fun selectUploadBatch(assets: List<LocalAsset>, maxBytes: Long = MAX_UPLOAD_BYTES_PER_RUN): List<LocalAsset> {
        val batch = mutableListOf<LocalAsset>()
        var cumulative = 0L
        for (asset in assets) {
            if (batch.isNotEmpty() && cumulative + asset.size > maxBytes) break
            batch.add(asset)
            cumulative += asset.size
        }
        return batch
    }

    private suspend fun hashOf(asset: LocalAsset): String =
        hashCache.get(asset.uri.toString(), asset.size, asset.lastModified)
            ?: FileHasher.md5(
                context,
                asset.uri,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) toMediaStoreUri(asset) else null
            ).also {
                hashCache.put(asset.uri.toString(), asset.size, asset.lastModified, it)
            }

    /**
     * Queries `POST /api/photos/check-hashes` for a single batch of MD5 hashes and returns
     * a map of each hash to its server existence boolean.
     */
    suspend fun checkHashesBatch(hashes: List<String>): Map<String, Boolean> {
        if (hashes.isEmpty()) return emptyMap()
        val response = photoApi.checkHashes(CheckHashesRequest(hashes))
        return hashes.associateWith { hash -> response.isExisting(hash) }
    }

    /**
     * Computes MD5 hashes for [assets] (using [hashCache] first and persisting newly computed
     * hashes to local storage) and queries the remote server in batches of [batchSize] (50 by
     * default) via `POST /api/photos/check-hashes`. Invokes [onBatchVerified] after each batch
     * with the per-asset existence status returned by the server.
     */
    suspend fun verifyFolderAssetsHashesInBatches(
        assets: List<LocalAsset>,
        batchSize: Int = HASH_CHECK_BATCH_SIZE,
        onBatchVerified: suspend (List<Pair<LocalAsset, Boolean>>) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (assets.isEmpty()) return@withContext
        val effectiveBatchSize = maxOf(1, batchSize)
        val pendingBatch = mutableListOf<Pair<LocalAsset, String>>()

        suspend fun flushBatch() {
            if (pendingBatch.isEmpty()) return
            hashCache.persist()
            val batchSnapshot = pendingBatch.toList()
            pendingBatch.clear()
            val distinctHashes = batchSnapshot.map { it.second }.distinct()
            try {
                val statusByHash = checkHashesBatch(distinctHashes)
                val results = batchSnapshot.map { (asset, hash) ->
                    asset to (statusByHash[hash] == true)
                }
                onBatchVerified(results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "verifyFolderAssetsHashesInBatches: checkHashes failed for batch of ${batchSnapshot.size}: ${e.message}")
            }
        }

        for (asset in assets) {
            val hash = try {
                hashOf(asset)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "verifyFolderAssetsHashesInBatches: failed to hash ${asset.displayName}: ${e.message}")
                null
            }
            if (hash != null) {
                pendingBatch.add(asset to hash)
                if (pendingBatch.size >= effectiveBatchSize) {
                    flushBatch()
                }
            }
        }
        flushBatch()
    }

    fun removeCachedHashesForAssets(assets: List<LocalAsset>) {
        if (assets.isEmpty()) return
        hashCache.removeAll(assets.map { it.uri.toString() })
    }

    fun removeCachedHashesForFolder(folderUri: Uri) {
        hashCache.removeForFolder(folderUri.toString())
    }

    fun retainCachedHashesForFolder(folderUri: Uri, existingAssets: List<LocalAsset>) {
        hashCache.retainForFolder(
            folderUri.toString(),
            existingAssets.map { it.uri.toString() }.toSet()
        )
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

    private suspend fun fetchAllSpaceGarbagePhotos(spaceId: String): List<Photo> {
        val all = mutableListOf<Photo>()
        var page = 0
        while (true) {
            val response = photoApi.listGarbageForSpace(spaceId, page, SPACE_PAGE_SIZE)
            all += response.content
            if (response.page.isLast) break
            page++
        }
        return all
    }

    suspend fun uploadAsset(
        asset: LocalAsset,
        spaceId: String,
        onProgress: ((uploadedChunks: Int, totalChunks: Int) -> Unit)? = null,
        onTaskCreated: ((taskId: String) -> Unit)? = null
    ): TaskDTO? = withContext(Dispatchers.IO) {
        val uploadId = UUID.randomUUID().toString()
        val totalChunks = maxOf(1, ceil(asset.size.toDouble() / CHUNK_SIZE).toInt())
        val mediaType = asset.mimeType.toMediaTypeOrNull()

        var completionTask: TaskDTO? = null
        val mediaStoreUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) toMediaStoreUri(asset) else null
        val input = FileHasher.openUnredactedInputStream(context, asset.uri, mediaStoreUri)
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
                chunkIndex++
                onProgress?.invoke(chunkIndex, totalChunks)
                completionTask = response.task
            }
        }

        completionTask?.let { task ->
            onTaskCreated?.invoke(task.id)
            return@withContext waitForProcessing(task.id)
        }
        null
    }

    private suspend fun waitForProcessing(taskId: String): TaskDTO {
        val deadline = System.currentTimeMillis() + TASK_POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val task = taskApi.get(taskId)
            when (task.status) {
                "COMPLETED", "IGNORED", "TRASHED" -> return task
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

    suspend fun restoreInSpace(photoIds: List<String>) {
        if (photoIds.isEmpty()) return
        photoApi.restoreFromGarbage(photoIds)
    }

    /** Moves [assets] (as returned by [LocalTrash.scan]) back to their original relativeDir under the synced folder root. */
    suspend fun restoreLocalAssets(folderUri: Uri, assets: List<LocalAsset>) = withContext(Dispatchers.IO) {
        for (asset in assets) {
            LocalTrash.restoreFromTrash(context, folderUri, asset)
        }
    }

    // Backs the reactive "also remove this from my phone" side of moving a Space photo to garbage
    // from the app UI (SpaceDetailScreen/PhotoDetailScreen), for a Space currently synced to
    // [folderUri] - finds each local file matching one of [hashes], via SafDir's batched scan (see
    // its kdoc - a naive per-file DocumentFile scan took ~40s for an 843-file camera roll).
    suspend fun findLocalAssetsForHashes(folderUri: Uri, hashes: Set<String>): List<LocalAsset> = withContext(Dispatchers.IO) {
        if (hashes.isEmpty()) {
            Log.w(TAG, "findLocalAssetsForHashes: called with no hashes, nothing to do")
            return@withContext emptyList()
        }
        Log.d(TAG, "findLocalAssetsForHashes: scanning $folderUri for ${hashes.size} hash(es)")
        val localAssets = LocalFolderScanner.scan(context, folderUri)
        Log.d(TAG, "findLocalAssetsForHashes: scanned ${localAssets.size} local asset(s)")
        val matched = localAssets.filter { hashOf(it) in hashes }
        Log.d(TAG, "findLocalAssetsForHashes: matched ${matched.size} of ${hashes.size} requested hash(es)")
        matched
    }

    // Fallback path for trashLocalFilesForHashes' callers when a matched asset can't be resolved to
    // a MediaStore Uri (see toMediaStoreUri) - moves it into the app-managed .iPhotoTrash folder
    // instead, silently and without a system dialog, since it isn't a MediaStore-indexed location.
    suspend fun trashLocalFilesInAppFolder(folderUri: Uri, assets: List<LocalAsset>) = withContext(Dispatchers.IO) {
        if (assets.isEmpty()) return@withContext
        var trashedOk = 0
        for (asset in assets) {
            val moved = LocalTrash.moveToTrash(context, folderUri, asset)
            if (moved) trashedOk++
            Log.d(TAG, "trashLocalFilesInAppFolder: ${asset.displayName} -> moveToTrash=$moved")
        }
        Log.d(TAG, "trashLocalFilesInAppFolder: done, trashedOk=$trashedOk of ${assets.size}")
        hashCache.persist()
    }

    fun resolveRelativePath(asset: LocalAsset): String? {
        try {
            if (DocumentsContract.isDocumentUri(context, asset.uri) || DocumentsContract.isTreeUri(asset.uri)) {
                val docId = DocumentsContract.getDocumentId(asset.uri)
                if (docId.startsWith("primary:")) {
                    val rawPath = docId.removePrefix("primary:")
                    val file = java.io.File(android.os.Environment.getExternalStorageDirectory(), rawPath)
                    val parent = file.parentFile
                    if (parent != null) {
                        val base = android.os.Environment.getExternalStorageDirectory().absolutePath
                        if (parent.absolutePath.startsWith(base)) {
                            val rel = parent.absolutePath.removePrefix(base).trim('/')
                            return if (rel.isEmpty()) "" else "$rel/"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "resolveRelativePath failed: ${e.message}")
        }
        return null
    }

    // Resolves a scanned local [asset] (living under a SAF tree the user picked) to its MediaStore
    // content Uri, when that SAF location happens to also be MediaStore-indexed - true for standard
    // camera-roll locations like DCIM/Camera, which is what this feature is built for. Lets the
    // caller use MediaStore.createTrashRequest or send to external editors (like Google Photos).
    fun toMediaStoreUri(asset: LocalAsset, preferredRelPath: String? = null): Uri? {
        val collection = if (asset.mimeType.startsWith("video/")) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val relPath = preferredRelPath ?: resolveRelativePath(asset)
        var filePath: String? = null
        try {
            if (DocumentsContract.isDocumentUri(context, asset.uri) || DocumentsContract.isTreeUri(asset.uri)) {
                val docId = DocumentsContract.getDocumentId(asset.uri)
                if (docId.startsWith("primary:")) {
                    val rawPath = docId.removePrefix("primary:")
                    val file = java.io.File(android.os.Environment.getExternalStorageDirectory(), rawPath)
                    if (file.exists()) {
                        filePath = file.absolutePath
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "toMediaStoreUri: could not parse filePath from docId: ${e.message}")
        }

        // 1. Query MediaStore by DATA (file path) if known
        if (filePath != null) {
            try {
                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DATA} = ?"
                val selectionArgs = arrayOf(filePath)
                context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        return ContentUris.withAppendedId(collection, id)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "toMediaStoreUri: DATA query failed: ${e.message}")
            }
        }

        // 2. Query MediaStore by DISPLAY_NAME and RELATIVE_PATH
        if (relPath != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
                val selectionArgs = arrayOf(asset.displayName, relPath)
                context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        return ContentUris.withAppendedId(collection, id)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "toMediaStoreUri: DISPLAY_NAME + RELATIVE_PATH query failed: ${e.message}")
            }
        }

        // 3. Query MediaStore by DISPLAY_NAME alone
        try {
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(asset.displayName)
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    return ContentUris.withAppendedId(collection, id)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "toMediaStoreUri: DISPLAY_NAME query failed: ${e.message}")
        }

        // 4. Try MediaStore.getMediaUri on Android Q+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val docUri = if (DocumentsContract.isTreeUri(asset.uri)) {
                    val docId = DocumentsContract.getDocumentId(asset.uri)
                    DocumentsContract.buildDocumentUri(asset.uri.authority ?: "com.android.externalstorage.documents", docId)
                } else {
                    asset.uri
                }
                val genericUri = MediaStore.getMediaUri(context, docUri)
                    ?: MediaStore.getMediaUri(context, asset.uri)
                if (genericUri != null) {
                    val id = ContentUris.parseId(genericUri)
                    return ContentUris.withAppendedId(collection, id)
                }
            } catch (e: Exception) {
                Log.d(TAG, "toMediaStoreUri: getMediaUri failed for ${asset.uri}: ${e.message}")
            }
        }

        return null
    }

    fun resolveFilePath(asset: LocalAsset): String? {
        try {
            if (DocumentsContract.isDocumentUri(context, asset.uri) || DocumentsContract.isTreeUri(asset.uri)) {
                val docId = DocumentsContract.getDocumentId(asset.uri)
                if (docId.startsWith("primary:")) {
                    val rawPath = docId.removePrefix("primary:")
                    val file = java.io.File(android.os.Environment.getExternalStorageDirectory(), rawPath)
                    return file.absolutePath
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "resolveFilePath failed: ${e.message}")
        }
        return null
    }

    // Resolves a scanned local [asset] to its MediaStore content Uri.
    // If not yet indexed in MediaStore, asks Android's MediaScanner to index the original physical file,
    // avoiding creating any duplicate copies of the file before editing.
    suspend fun getOrScanMediaStoreUri(asset: LocalAsset, preferredRelPath: String? = null): Uri? {
        // 1. Check if it's already indexed in MediaStore
        val existingUri = toMediaStoreUri(asset, preferredRelPath)
        if (existingUri != null) {
            return existingUri
        }

        // 2. Scan the original file directly with MediaScannerConnection so MediaStore indexes it in-place
        val filePath = resolveFilePath(asset)
        if (filePath != null) {
            try {
                val scannedUri = suspendCancellableCoroutine<Uri?> { cont ->
                    MediaScannerConnection.scanFile(context, arrayOf(filePath), arrayOf(asset.mimeType)) { _, uri ->
                        if (cont.isActive) cont.resume(uri)
                    }
                }
                if (scannedUri != null) {
                    return scannedUri
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaScannerConnection.scanFile failed for $filePath", e)
            }
        }

        return null
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
        private const val TAG = "SyncRepository"
        const val HASH_CHECK_BATCH_SIZE = 50
        private const val SPACE_PAGE_SIZE = 200
        private const val CHUNK_SIZE = 1 * 1024 * 1024
        private const val TASK_POLL_INTERVAL_MS = 1000L
        private const val TASK_POLL_TIMEOUT_MS = 5 * 60 * 1000L
        // Default per-run upload budget for selectUploadBatch - chosen as a reasonable amount for
        // one ~15-minute cycle on typical mobile/wifi conditions without hammering a self-hosted
        // single-instance server harder than it's likely provisioned for.
        const val MAX_UPLOAD_BYTES_PER_RUN = 200L * 1024 * 1024
    }
}
