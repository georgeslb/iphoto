package org.iphoto.mobile.ui.sync

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.iphoto.mobile.AppContainer
import org.iphoto.mobile.data.SyncPrefs
import org.iphoto.mobile.model.SpaceDTO
import org.iphoto.mobile.network.SpaceApi
import org.iphoto.mobile.sync.LocalAsset
import org.iphoto.mobile.sync.LocalFolderScanner
import org.iphoto.mobile.sync.LocalTrash
import org.iphoto.mobile.sync.SyncRepository
import org.iphoto.mobile.sync.UploadForegroundService
import org.iphoto.mobile.sync.UploadItem
import org.iphoto.mobile.sync.UploadStatus
import java.util.Collections
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val TAG = "SyncViewModel"

data class SelectedFolder(
    val uri: Uri,
    val name: String
)

sealed interface GridItem {
    data class Header(val text: String, val assets: List<LocalAsset>) : GridItem
    data class Asset(val asset: LocalAsset) : GridItem
}

data class SyncUiState(
    val selectedFolders: List<SelectedFolder> = emptyList(),
    val spaces: List<SpaceDTO> = emptyList(),
    val folderContents: Map<Uri, List<GridItem>> = emptyMap(),
    val isLoadingSpaces: Boolean = false,
    val isLoadingFolderContents: Set<Uri> = emptySet(),
    val isSelectionMode: Boolean = false,
    val selectedAssets: Set<Uri> = emptySet(),
    val isUploading: Boolean = false,
    val isCheckingHashes: Boolean = false,
    val error: String? = null,
    val uploadedAssetKeys: Set<String> = emptySet(),
)

data class UploadVerificationResult(
    val uploaded: List<LocalAsset> = emptyList(),
    val alreadyImported: List<LocalAsset> = emptyList(),
    val alreadyInTrash: List<LocalAsset> = emptyList()
) {
    val allVerified: List<LocalAsset>
        get() = uploaded + alreadyImported + alreadyInTrash

    val size: Int
        get() = allVerified.size

    fun isNotEmpty(): Boolean = allVerified.isNotEmpty()

    fun summaryMessage(singleAsset: Boolean = false): String {
        val uploadedCount = uploaded.size
        val ignoredCount = alreadyImported.size
        val trashedCount = alreadyInTrash.size
        if (singleAsset && size == 1) {
            return when {
                ignoredCount == 1 -> "Already imported"
                trashedCount == 1 -> "Already imported and in trash"
                else -> "Uploaded successfully"
            }
        }
        val parts = mutableListOf<String>()
        if (uploadedCount > 0) parts.add("Uploaded $uploadedCount photo(s)")
        if (ignoredCount > 0) {
            parts.add(if (uploadedCount == 0 && trashedCount == 0 && ignoredCount == 1) "Already imported" else "$ignoredCount already imported")
        }
        if (trashedCount > 0) {
            parts.add(if (uploadedCount == 0 && ignoredCount == 0 && trashedCount == 1) "Already imported and in trash" else "$trashedCount already imported and in trash")
        }
        return parts.joinToString(", ").ifEmpty { "Upload failed on server" }
    }
}

class SyncViewModel(
    private val appContext: Context,
    private val spaceApi: SpaceApi,
    private val syncRepository: SyncRepository,
    private val syncPrefs: SyncPrefs,
    private val container: AppContainer? = null,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    var selectedAssetForDetail by mutableStateOf<LocalAsset?>(null)
        private set

    fun openAssetDetail(asset: LocalAsset) {
        selectedAssetForDetail = asset
    }

    fun clearAssetDetail() {
        selectedAssetForDetail = null
    }

    fun getMediaStoreUri(asset: LocalAsset): Uri? = syncRepository.toMediaStoreUri(asset)

    fun getMediaUrisForAssets(assets: List<LocalAsset>): List<Uri> {
        return assets.mapNotNull { getMediaStoreUri(it) }
    }

    private fun clearUploadedStatusForAssets(assets: List<LocalAsset>, explicitFolderUri: Uri? = null) {
        if (assets.isEmpty()) return
        syncRepository.removeCachedHashesForAssets(assets)
        assets.forEach { asset ->
            val matchedFolderUri = explicitFolderUri ?: findAssetAndFolder(asset.uri)?.second?.uri
            if (matchedFolderUri != null) {
                syncPrefs.removeUploadedAsset(matchedFolderUri.toString(), asset.relativeDir, asset.displayName)
            } else {
                _uiState.value.selectedFolders.forEach { folder ->
                    syncPrefs.removeUploadedAsset(folder.uri.toString(), asset.relativeDir, asset.displayName)
                }
            }
        }
        _uiState.update { it.copy(uploadedAssetKeys = syncPrefs.getUploadedAssetKeys()) }
    }

    fun onAssetsTrashed(assets: List<LocalAsset>) {
        clearUploadedStatusForAssets(assets)
        val assetUris = assets.map { it.uri }.toSet()
        _uiState.update { current ->
            val updated = current.folderContents.mapValues { (_, items) ->
                items.filter { !(it is GridItem.Asset && it.asset.uri in assetUris) }
            }
            val remainingSelected = current.selectedAssets - assetUris
            current.copy(
                folderContents = updated,
                isSelectionMode = remainingSelected.isNotEmpty(),
                selectedAssets = remainingSelected
            )
        }
        refreshAllFolders()
    }

    @Deprecated("Sync tab exclusively uses system trash authorization (MediaStore.createTrashRequest).")
    fun trashAssetsDirectly(assets: List<LocalAsset>) {
        val targets = assets.mapNotNull { findAssetAndFolder(it.uri) }
        onAssetsTrashed(assets)
        viewModelScope.launch {
            targets.forEach { (targetAsset, folder) ->
                try {
                    val deleted = DocumentsContract.deleteDocument(appContext.contentResolver, targetAsset.uri)
                    if (!deleted) {
                        LocalTrash.moveToTrash(appContext, folder.uri, targetAsset)
                    }
                } catch (e: Exception) {
                    try {
                        LocalTrash.moveToTrash(appContext, folder.uri, targetAsset)
                    } catch (e2: Exception) {
                        Log.w(TAG, "Failed to trash asset: ${targetAsset.uri}", e2)
                    }
                }
            }
        }
    }

    suspend fun uploadAssetDirectly(asset: LocalAsset, spaceId: String): org.iphoto.mobile.model.TaskDTO? {
        val task = syncRepository.uploadAsset(asset, spaceId)
        if (task != null && task.status != "FAILED") {
            container?.notifyLibraryChanged()
        }
        return task
    }

    suspend fun uploadAndVerify(
        assets: List<LocalAsset>,
        spaceId: String,
        targetFolderUri: Uri? = null
    ): UploadVerificationResult = withContext(Dispatchers.IO) {
        val uploaded = Collections.synchronizedList(mutableListOf<LocalAsset>())
        val alreadyImported = Collections.synchronizedList(mutableListOf<LocalAsset>())
        val alreadyInTrash = Collections.synchronizedList(mutableListOf<LocalAsset>())
        _uiState.update { it.copy(isUploading = true, error = null) }
        UploadForegroundService.start(appContext)

        val semaphore = Semaphore(3)
        try {
            coroutineScope {
                assets.map { asset ->
                    async {
                        semaphore.withPermit {
                            val folderUri = targetFolderUri ?: findAssetAndFolder(asset.uri)?.second?.uri
                            val uploadItem = UploadItem(
                                asset = asset,
                                folderUri = folderUri,
                                spaceId = spaceId,
                                status = UploadStatus.UPLOADING
                            )
                            container?.uploadManager?.registerOrUpdate(uploadItem)

                            try {
                                // Step 1: Upload chunks. Step 2: Poll back office task until finished.
                                val task = syncRepository.uploadAsset(
                                    asset = asset,
                                    spaceId = spaceId,
                                    onProgress = { uploadedChunks, total ->
                                        val fraction = uploadedChunks.toFloat() / maxOf(1, total)
                                        container?.uploadManager?.updateProgress(uploadItem.id, fraction)
                                    },
                                    onTaskCreated = { taskId ->
                                        container?.uploadManager?.updateStatus(uploadItem.id, UploadStatus.PROCESSING, taskId)
                                    }
                                )
                                if (task != null && task.status != "FAILED") {
                                    val folderUriStr = folderUri?.toString().orEmpty()
                                    syncPrefs.recordUploadedAsset(folderUriStr, asset.relativeDir, asset.displayName)
                                    val key = SyncPrefs.encodeUploadedKey(folderUriStr, asset.relativeDir, asset.displayName)
                                    _uiState.update { it.copy(uploadedAssetKeys = it.uploadedAssetKeys + key) }

                                    val terminalStatus = when (task.status) {
                                        "IGNORED" -> {
                                            alreadyImported.add(asset)
                                            UploadStatus.IGNORED
                                        }
                                        "TRASHED" -> {
                                            alreadyInTrash.add(asset)
                                            UploadStatus.TRASHED
                                        }
                                        else -> {
                                            uploaded.add(asset)
                                            UploadStatus.COMPLETED
                                        }
                                    }
                                    container?.uploadManager?.updateStatus(uploadItem.id, terminalStatus, task.id, task.photoId)
                                } else {
                                    container?.uploadManager?.updateFailed(uploadItem.id, "Import failed on server")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to upload and verify ${asset.displayName}", e)
                                container?.uploadManager?.updateFailed(uploadItem.id, e.message ?: "Upload failed")
                            }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            _uiState.update { it.copy(isUploading = false) }
            if (uploaded.isNotEmpty() || alreadyImported.isNotEmpty() || alreadyInTrash.isNotEmpty()) {
                container?.notifyLibraryChanged()
            }
        }
        UploadVerificationResult(
            uploaded = uploaded.toList(),
            alreadyImported = alreadyImported.toList(),
            alreadyInTrash = alreadyInTrash.toList()
        )
    }

    fun getFolderRelativePath(folderUri: Uri): String? {
        return try {
            val treeDocId = DocumentsContract.getTreeDocumentId(folderUri)
            if (treeDocId.startsWith("primary:")) {
                val raw = treeDocId.removePrefix("primary:").trim('/')
                if (raw.isEmpty()) "" else "$raw/"
            } else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun prepareMediaStoreUriForEditing(asset: LocalAsset): Uri? {
        val folder = findAssetAndFolder(asset.uri)?.second
        val folderRelPath = folder?.let { getFolderRelativePath(it.uri) }
        val targetRelPath = if (!folderRelPath.isNullOrEmpty()) {
            if (asset.relativeDir.isNotEmpty()) {
                "$folderRelPath${asset.relativeDir.trim('/')}/"
            } else {
                folderRelPath
            }
        } else null
        return syncRepository.getOrScanMediaStoreUri(asset, targetRelPath)
    }

    private var contentObserver: ContentObserver? = null
    private val folderScanJobs = mutableMapOf<Uri, Job>()
    private val folderHashJobs = mutableMapOf<Uri, Job>()
    private val folderHashSignatures = mutableMapOf<Uri, List<String>>()
    private val hashCheckMutex = Mutex()

    init {
        loadSpaces()
        loadPersistedFolders()
        loadUploadedAssets()
        setupMediaStoreObserver()
        setupFolderChangeObserver()
        refreshAllFolders()
    }

    private fun loadUploadedAssets() {
        _uiState.update { it.copy(uploadedAssetKeys = syncPrefs.getUploadedAssetKeys()) }
    }

    fun isAssetUploaded(folderUri: Uri, asset: LocalAsset): Boolean {
        val exactKey = SyncPrefs.encodeUploadedKey(folderUri.toString(), asset.relativeDir, asset.displayName)
        if (_uiState.value.uploadedAssetKeys.contains(exactKey)) return true
        val decodedFolder = Uri.decode(folderUri.toString())
        val fallbackKey = SyncPrefs.encodeUploadedKey(decodedFolder, asset.relativeDir, asset.displayName)
        return _uiState.value.uploadedAssetKeys.contains(fallbackKey)
    }

    private fun setupFolderChangeObserver() {
        if (container != null) {
            viewModelScope.launch {
                container.folderChangeTrigger.collect { folderUri ->
                    _uiState.value.selectedFolders.find { it.uri == folderUri }?.let { folder ->
                        refreshFolder(folder.uri, folder.name)
                    }
                }
            }
        }
    }

    private fun setupMediaStoreObserver() {
        try {
            val looper = Looper.getMainLooper() ?: return
            val handler = Handler(looper)
            val observer = object : ContentObserver(handler) {
                private var refreshJob: kotlinx.coroutines.Job? = null
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    // Debounce rapid changes (e.g. saving file + generating thumbnail in MediaStore)
                    refreshJob?.cancel()
                    refreshJob = viewModelScope.launch {
                        kotlinx.coroutines.delay(800)
                        refreshAllFolders()
                    }
                }
            }
            contentObserver = observer
            appContext.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            appContext.contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to register MediaStore content observer: ${e.message}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        folderScanJobs.values.forEach { it.cancel() }
        folderScanJobs.clear()
        folderHashJobs.values.forEach { it.cancel() }
        folderHashJobs.clear()
        folderHashSignatures.clear()
        updateCheckingHashesState()
        contentObserver?.let {
            try {
                appContext.contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister MediaStore content observer", e)
            }
        }
    }

    fun refreshAllFolders() {
        _uiState.value.selectedFolders.forEach { folder ->
            refreshFolder(folder.uri, folder.name)
        }
    }

    private fun loadPersistedFolders() {
        val folders = syncPrefs.loadSelectedFolders()
        _uiState.update { it.copy(selectedFolders = folders) }
    }

    fun refreshSpaces() {
        loadSpaces()
    }

    private fun loadSpaces() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSpaces = true, error = null) }
            try {
                _uiState.update { it.copy(spaces = spaceApi.list(), isLoadingSpaces = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoadingSpaces = false, error = e.message) }
            }
        }
    }

    fun onTabSelected(folder: SelectedFolder) {
        if (!_uiState.value.folderContents.containsKey(folder.uri)) {
            refreshFolder(folder.uri, folder.name)
        }
    }

    fun refreshFolder(uri: Uri, displayName: String) {
        folderScanJobs.remove(uri)?.cancel()
        folderScanJobs[uri] = viewModelScope.launch {
            // Only show a full-screen loading indicator on the initial load when we don't have
            // cached contents in memory yet, so background refreshes never blank out the grid.
            _uiState.update {
                if (it.folderContents.containsKey(uri)) it
                else it.copy(isLoadingFolderContents = it.isLoadingFolderContents + uri)
            }
            try {
                val assets = withContext(ioDispatcher) {
                    val scanned = LocalFolderScanner.scan(appContext, uri)
                        .sortedByDescending { asset -> asset.lastModified }
                    syncPrefs.retainUploadedAssetsForFolder(
                        uri.toString(),
                        scanned.map { it.relativeDir to it.displayName }.toSet()
                    )
                    syncRepository.retainCachedHashesForFolder(uri, scanned)
                    scanned
                }

                val groupedAndFlattened = assets.groupBy {
                    val date = java.time.Instant.ofEpochMilli(it.lastModified).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
                }.flatMap { (header, dayAssets) ->
                    listOf(GridItem.Header(header, dayAssets)) + dayAssets.map { GridItem.Asset(it) }
                }

                val currentUploadedKeys = syncPrefs.getUploadedAssetKeys()
                _uiState.update {
                    it.copy(
                        folderContents = it.folderContents + (uri to groupedAndFlattened),
                        isLoadingFolderContents = it.isLoadingFolderContents - uri,
                        uploadedAssetKeys = currentUploadedKeys
                    )
                }

                verifyFolderHashesInBackground(uri, assets)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to scan folder \"$displayName\"", isLoadingFolderContents = it.isLoadingFolderContents - uri) }
            }
        }
    }

    private fun updateCheckingHashesState() {
        folderHashJobs.entries.removeAll { !it.value.isActive }
        val anyActive = folderHashJobs.values.any { it.isActive }
        _uiState.update { it.copy(isCheckingHashes = anyActive) }
    }

    private fun verifyFolderHashesInBackground(folderUri: Uri, assets: List<LocalAsset>, force: Boolean = false) {
        val signatures = assets.map { "${it.uri}|${it.size}|${it.lastModified}" }
        val existingJob = folderHashJobs[folderUri]
        if (!force && existingJob?.isActive == true && folderHashSignatures[folderUri] == signatures) {
            return
        }
        existingJob?.cancel()
        folderHashJobs.remove(folderUri)
        if (assets.isEmpty()) {
            folderHashSignatures.remove(folderUri)
            updateCheckingHashesState()
            return
        }
        folderHashSignatures[folderUri] = signatures
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            _uiState.update { it.copy(isCheckingHashes = true) }
            try {
                hashCheckMutex.withLock {
                    syncRepository.verifyFolderAssetsHashesInBatches(
                        assets = assets,
                        batchSize = SyncRepository.HASH_CHECK_BATCH_SIZE
                    ) { batchResults ->
                        val statuses = batchResults.map { (asset, existsOnServer) ->
                            (asset.relativeDir to asset.displayName) to existsOnServer
                        }
                        syncPrefs.applyBatchHashCheckStatuses(folderUri.toString(), statuses)
                        val updatedKeys = syncPrefs.getUploadedAssetKeys()
                        _uiState.update { it.copy(uploadedAssetKeys = updatedKeys) }
                    }
                }
            } finally {
                folderHashJobs.remove(folderUri)
                updateCheckingHashesState()
            }
        }
        folderHashJobs[folderUri] = job
        updateCheckingHashesState()
        job.start()
    }

    /**
     * Forces check_hashes remote calls by 50-asset batches across all selected folders,
     * using the cached MD5 hashes where available.
     */
    fun forceCheckHashes() {
        val currentFolders = _uiState.value.selectedFolders
        if (currentFolders.isEmpty()) return
        currentFolders.forEach { folder ->
            val items = _uiState.value.folderContents[folder.uri]
            val assets = items?.filterIsInstance<GridItem.Asset>()?.map { it.asset }
            if (assets != null && assets.isNotEmpty()) {
                verifyFolderHashesInBackground(folder.uri, assets, force = true)
            } else {
                refreshFolder(folder.uri, folder.name)
            }
        }
    }

    fun addFolder(uri: Uri, displayName: String) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            appContext.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to take read/write persistable permission, falling back to read: ${e.message}")
            try {
                appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to take read persistable permission", e2)
            }
        }
        val folder = SelectedFolder(uri, displayName)
        val updatedFolders = _uiState.value.selectedFolders + folder
        _uiState.update { it.copy(selectedFolders = updatedFolders) }
        syncPrefs.saveSelectedFolders(updatedFolders)
        refreshFolder(uri, displayName)
    }

    fun removeFolder(folder: SelectedFolder) {
        folderScanJobs.remove(folder.uri)?.cancel()
        folderHashJobs.remove(folder.uri)?.cancel()
        folderHashSignatures.remove(folder.uri)
        val updatedFolders = _uiState.value.selectedFolders - folder
        syncPrefs.saveSelectedFolders(updatedFolders)
        syncPrefs.removeUploadedAssetsForFolder(folder.uri.toString())
        syncRepository.removeCachedHashesForFolder(folder.uri)
        val updatedUploadedKeys = syncPrefs.getUploadedAssetKeys()
        _uiState.update {
            it.copy(
                selectedFolders = updatedFolders,
                folderContents = it.folderContents - folder.uri,
                uploadedAssetKeys = updatedUploadedKeys
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(isSelectionMode = false, selectedAssets = emptySet()) }
    }

    fun enterSelectionMode(asset: LocalAsset) {
        _uiState.update { it.copy(isSelectionMode = true, selectedAssets = it.selectedAssets + asset.uri) }
    }

    fun toggleAssetSelection(asset: LocalAsset) {
        _uiState.update {
            val newSelected = if (it.selectedAssets.contains(asset.uri)) it.selectedAssets - asset.uri else it.selectedAssets + asset.uri
            it.copy(selectedAssets = newSelected, isSelectionMode = newSelected.isNotEmpty())
        }
    }

    fun toggleDaySelection(assets: List<LocalAsset>) {
        val uris = assets.map { it.uri }.toSet()
        _uiState.update {
            val allSelected = it.selectedAssets.containsAll(uris)
            val newSelected = if (allSelected) it.selectedAssets - uris else it.selectedAssets + uris
            it.copy(selectedAssets = newSelected, isSelectionMode = newSelected.isNotEmpty())
        }
    }

    fun findAssetAndFolder(assetUri: Uri): Pair<LocalAsset, SelectedFolder>? {
        _uiState.value.folderContents.forEach { (folderUri, items) ->
            items.filterIsInstance<GridItem.Asset>().forEach { gridAsset ->
                if (gridAsset.asset.uri == assetUri) {
                    val folder = _uiState.value.selectedFolders.find { it.uri == folderUri }
                    if (folder != null) {
                        return Pair(gridAsset.asset, folder)
                    }
                }
            }
        }
        return null
    }

    fun uploadSingleAsset(assetUri: Uri, spaceId: String, deleteAfter: Boolean) {
        findAssetAndFolder(assetUri)?.let { (asset, folder) ->
            if (deleteAfter) {
                clearUploadedStatusForAssets(listOf(asset), folder.uri)
                // Optimistically remove from state in memory immediately
                _uiState.update { current ->
                    val updatedContents = current.folderContents.mapValues { (_, items) ->
                        items.filter { !(it is GridItem.Asset && it.asset.uri == asset.uri) }
                    }
                    current.copy(folderContents = updatedContents)
                }
            }
            if (container != null) {
                container.uploadManager.enqueue(asset, folder.uri, spaceId, deleteAfter)
            } else {
                viewModelScope.launch {
                    _uiState.update { it.copy(isUploading = true, error = null) }
                    try {
                        syncRepository.uploadAsset(asset, spaceId)
                        if (deleteAfter) {
                            LocalTrash.moveToTrash(appContext, folder.uri, asset)
                        }
                    } catch (e: Exception) {
                        _uiState.update { it.copy(error = "Upload failed: ${e.message}") }
                    } finally {
                        _uiState.update { it.copy(isUploading = false) }
                        if (deleteAfter) {
                            refreshFolder(folder.uri, folder.name)
                        }
                    }
                }
            }
        }
    }

    fun uploadSelectedAssets(spaceId: String, deleteAfter: Boolean) {
        val assetsWithFolder = mutableListOf<Pair<LocalAsset, Uri?>>()
        val selected = _uiState.value.selectedAssets

        _uiState.value.folderContents.forEach { (folderUri, items) ->
            items.filterIsInstance<GridItem.Asset>().forEach { gridAsset ->
                if (selected.contains(gridAsset.asset.uri)) {
                    assetsWithFolder.add(gridAsset.asset to folderUri)
                }
            }
        }

        if (assetsWithFolder.isEmpty()) {
            clearSelection()
            return
        }

        if (deleteAfter) {
            assetsWithFolder.forEach { (asset, folderUri) ->
                clearUploadedStatusForAssets(listOf(asset), folderUri)
            }
            // Optimistically remove from state in memory immediately
            _uiState.update { current ->
                val updatedContents = current.folderContents.mapValues { (_, items) ->
                    items.filter { !(it is GridItem.Asset && it.asset.uri in selected) }
                }
                current.copy(folderContents = updatedContents, isSelectionMode = false, selectedAssets = emptySet())
            }
        } else {
            clearSelection()
        }

        if (container != null) {
            container.uploadManager.enqueueAll(assetsWithFolder, spaceId, deleteAfter)
        } else {
            viewModelScope.launch {
                _uiState.update { it.copy(isUploading = true, error = null) }
                try {
                    assetsWithFolder.forEach { (asset, _) ->
                        syncRepository.uploadAsset(asset, spaceId)
                    }
                } catch (e: Exception) {
                    _uiState.update { it.copy(error = "Upload failed: ${e.message}") }
                } finally {
                    _uiState.update { it.copy(isUploading = false) }
                }
            }
        }
    }

    fun deleteSelectedAssets() {
        val selected = _uiState.value.selectedAssets
        if (selected.isEmpty()) {
            clearSelection()
            return
        }

        val targets = selected.mapNotNull { findAssetAndFolder(it) }
        targets.forEach { (asset, folder) ->
            clearUploadedStatusForAssets(listOf(asset), folder.uri)
        }

        // Optimistically remove from state in memory so UI updates instantly
        _uiState.update { current ->
            val updatedContents = current.folderContents.mapValues { (_, items) ->
                items.filter { !(it is GridItem.Asset && it.asset.uri in selected) }
            }
            current.copy(folderContents = updatedContents, isSelectionMode = false, selectedAssets = emptySet())
        }

        viewModelScope.launch {
            targets.forEach { (asset, folder) ->
                try {
                    val deleted = DocumentsContract.deleteDocument(appContext.contentResolver, asset.uri)
                    if (!deleted) {
                        LocalTrash.moveToTrash(appContext, folder.uri, asset)
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "deleteDocument SecurityException, falling back to LocalTrash: ${e.message}")
                    try {
                        LocalTrash.moveToTrash(appContext, folder.uri, asset)
                    } catch (e2: Throwable) {
                        Log.e(TAG, "Failed to move to trash after SecurityException: ${asset.uri}", e2)
                    }
                } catch (e: Throwable) {
                    try {
                        LocalTrash.moveToTrash(appContext, folder.uri, asset)
                    } catch (e2: Throwable) {
                        Log.e(TAG, "Failed to delete or trash asset: ${asset.uri}", e2)
                    }
                }
            }
        }
    }

    fun trashSingleAsset(asset: LocalAsset) {
        val targetPair = findAssetAndFolder(asset.uri)
        clearUploadedStatusForAssets(listOf(asset), targetPair?.second?.uri)

        // Optimistically remove from state in memory so UI updates instantly
        _uiState.update { current ->
            val updatedContents = current.folderContents.mapValues { (_, items) ->
                items.filter { !(it is GridItem.Asset && it.asset.uri == asset.uri) }
            }
            current.copy(folderContents = updatedContents)
        }
        viewModelScope.launch {
            targetPair?.let { (targetAsset, folder) ->
                try {
                    val deleted = DocumentsContract.deleteDocument(appContext.contentResolver, targetAsset.uri)
                    if (!deleted) {
                        LocalTrash.moveToTrash(appContext, folder.uri, targetAsset)
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "deleteDocument SecurityException, falling back to LocalTrash: ${e.message}")
                    try {
                        LocalTrash.moveToTrash(appContext, folder.uri, targetAsset)
                    } catch (e2: Throwable) {
                        Log.e(TAG, "Failed to move to trash after SecurityException: ${targetAsset.uri}", e2)
                    }
                } catch (e: Throwable) {
                    try {
                        LocalTrash.moveToTrash(appContext, folder.uri, targetAsset)
                    } catch (e2: Throwable) {
                        Log.e(TAG, "Failed to delete or trash asset: ${targetAsset.uri}", e2)
                    }
                }
            }
        }
    }

    fun deletePermanently(asset: LocalAsset) {
        val targetPair = findAssetAndFolder(asset.uri)
        clearUploadedStatusForAssets(listOf(asset), targetPair?.second?.uri)
        viewModelScope.launch {
            targetPair?.let { (targetAsset, folder) ->
                try {
                    val deleted = DocumentsContract.deleteDocument(appContext.contentResolver, targetAsset.uri)
                    if (!deleted) {
                        androidx.documentfile.provider.DocumentFile.fromSingleUri(appContext, targetAsset.uri)?.delete()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to permanently delete asset: ${targetAsset.uri}", e)
                } finally {
                    refreshFolder(folder.uri, folder.name)
                }
            }
        }
    }
}

class SyncViewModelFactory(
    private val appContext: Context,
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SyncViewModel(
            appContext,
            container.spaceApi,
            container.syncRepository(),
            container.syncPrefs,
            container
        ) as T
}
