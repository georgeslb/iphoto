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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.iphoto.mobile.AppContainer
import org.iphoto.mobile.data.SyncPrefs
import org.iphoto.mobile.model.SpaceDTO
import org.iphoto.mobile.network.SpaceApi
import org.iphoto.mobile.sync.LocalAsset
import org.iphoto.mobile.sync.LocalFolderScanner
import org.iphoto.mobile.sync.LocalTrash
import org.iphoto.mobile.sync.SyncRepository
import org.iphoto.mobile.sync.UploadItem
import org.iphoto.mobile.sync.UploadStatus
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
    val error: String? = null,
    val uploadedAssetKeys: Set<String> = emptySet(),
)

class SyncViewModel(
    private val appContext: Context,
    private val spaceApi: SpaceApi,
    private val syncRepository: SyncRepository,
    private val syncPrefs: SyncPrefs,
    private val container: AppContainer? = null
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

    fun onAssetsTrashed(assets: List<LocalAsset>) {
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

    fun trashAssetsDirectly(assets: List<LocalAsset>) {
        onAssetsTrashed(assets)
        viewModelScope.launch {
            assets.forEach { asset ->
                findAssetAndFolder(asset.uri)?.let { (targetAsset, folder) ->
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
    ): List<LocalAsset> {
        val successful = mutableListOf<LocalAsset>()
        _uiState.update { it.copy(isUploading = true, error = null) }
        try {
            for (asset in assets) {
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
                        onProgress = { uploaded, total ->
                            val fraction = uploaded.toFloat() / maxOf(1, total)
                            container?.uploadManager?.updateProgress(uploadItem.id, fraction)
                        },
                        onTaskCreated = { taskId ->
                            container?.uploadManager?.updateStatus(uploadItem.id, UploadStatus.PROCESSING, taskId)
                        }
                    )
                    if (task != null && task.status != "FAILED") {
                        successful.add(asset)
                        val folderUriStr = folderUri?.toString().orEmpty()
                        syncPrefs.recordUploadedAsset(folderUriStr, asset.relativeDir, asset.displayName)
                        val key = SyncPrefs.encodeUploadedKey(folderUriStr, asset.relativeDir, asset.displayName)
                        _uiState.update { it.copy(uploadedAssetKeys = it.uploadedAssetKeys + key) }

                        val terminalStatus = when (task.status) {
                            "IGNORED" -> UploadStatus.IGNORED
                            "TRASHED" -> UploadStatus.TRASHED
                            else -> UploadStatus.COMPLETED
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
        } finally {
            _uiState.update { it.copy(isUploading = false) }
            if (successful.isNotEmpty()) {
                container?.notifyLibraryChanged()
            }
        }
        return successful
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

    init {
        loadSpaces()
        loadPersistedFolders()
        loadUploadedAssets()
        setupMediaStoreObserver()
        setupFolderChangeObserver()
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
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingFolderContents = it.isLoadingFolderContents + uri) }
            try {
                val assets = withContext(Dispatchers.IO) {
                    LocalFolderScanner.scan(appContext, uri)
                        .sortedByDescending { asset -> asset.lastModified }
                }

                val groupedAndFlattened = assets.groupBy {
                    val date = java.time.Instant.ofEpochMilli(it.lastModified).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
                }.flatMap { (header, dayAssets) ->
                    listOf(GridItem.Header(header, dayAssets)) + dayAssets.map { GridItem.Asset(it) }
                }

                _uiState.update {
                    it.copy(
                        folderContents = it.folderContents + (uri to groupedAndFlattened),
                        isLoadingFolderContents = it.isLoadingFolderContents - uri
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to scan folder \"$displayName\"", isLoadingFolderContents = it.isLoadingFolderContents - uri) }
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
        val updatedFolders = _uiState.value.selectedFolders - folder
        syncPrefs.saveSelectedFolders(updatedFolders)
        _uiState.update {
            it.copy(
                selectedFolders = updatedFolders,
                folderContents = it.folderContents - folder.uri
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
        viewModelScope.launch {
            findAssetAndFolder(asset.uri)?.let { (targetAsset, folder) ->
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
