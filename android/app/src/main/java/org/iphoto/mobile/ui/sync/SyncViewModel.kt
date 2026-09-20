package org.iphoto.mobile.ui.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.iphoto.mobile.AppContainer
import org.iphoto.mobile.data.SyncPrefs
import org.iphoto.mobile.model.SpaceDTO
import org.iphoto.mobile.network.SpaceApi
import org.iphoto.mobile.sync.LocalAsset
import org.iphoto.mobile.sync.LocalFolderScanner
import org.iphoto.mobile.sync.LocalTrash
import org.iphoto.mobile.sync.SyncRepository
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
)

class SyncViewModel(
    private val appContext: Context,
    private val spaceApi: SpaceApi,
    private val syncRepository: SyncRepository,
    private val syncPrefs: SyncPrefs
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

    init {
        loadSpaces()
        loadPersistedFolders()
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
                val assets = LocalFolderScanner.scan(appContext, uri)
                    .sortedByDescending { asset -> asset.lastModified }

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
        appContext.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
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

    fun uploadSelectedAssets(spaceId: String, deleteAfter: Boolean) {
        val assetsToUpload = mutableListOf<LocalAsset>()
        val assetToFolderMap = mutableMapOf<Uri, Uri>()

        _uiState.value.folderContents.forEach { (folderUri, items) ->
            items.filterIsInstance<GridItem.Asset>().forEach { gridAsset ->
                if (_uiState.value.selectedAssets.contains(gridAsset.asset.uri)) {
                    assetsToUpload.add(gridAsset.asset)
                    assetToFolderMap[gridAsset.asset.uri] = folderUri
                }
            }
        }

        if (assetsToUpload.isEmpty()) {
            clearSelection()
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isUploading = true, error = null) }
            var successCount = 0
            val foldersToRefresh = mutableSetOf<Uri>()
            try {
                assetsToUpload.forEach { asset ->
                    syncRepository.uploadAsset(asset, spaceId)
                    if (deleteAfter) {
                        val folderUri = assetToFolderMap[asset.uri]
                        if (folderUri != null) {
                            LocalTrash.moveToTrash(appContext, folderUri, asset)
                            foldersToRefresh.add(folderUri)
                        } else {
                            Log.w(TAG, "Could not find folderUri for asset ${asset.uri} to trash it.")
                        }
                    }
                    successCount++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed after $successCount successes", e)
                _uiState.update { it.copy(error = "Upload failed: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isUploading = false) }
                clearSelection()
                if (deleteAfter) {
                    foldersToRefresh.forEach { folderUri ->
                        _uiState.value.selectedFolders.find { it.uri == folderUri }?.let { folder ->
                            refreshFolder(folder.uri, folder.name)
                        }
                    }
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

        // Optimistically remove from state in memory so UI updates instantly
        _uiState.update { current ->
            val updatedContents = current.folderContents.mapValues { (_, items) ->
                items.filter { !(it is GridItem.Asset && it.asset.uri in selected) }
            }
            current.copy(folderContents = updatedContents, isSelectionMode = false, selectedAssets = emptySet())
        }

        viewModelScope.launch {
            selected.forEach { assetUri ->
                findAssetAndFolder(assetUri)?.let { (asset, folder) ->
                    try {
                        val deleted = DocumentsContract.deleteDocument(appContext.contentResolver, asset.uri)
                        if (!deleted) {
                            LocalTrash.moveToTrash(appContext, folder.uri, asset)
                        }
                    } catch (e: Exception) {
                        try {
                            LocalTrash.moveToTrash(appContext, folder.uri, asset)
                        } catch (e2: Exception) {
                            Log.e(TAG, "Failed to delete or trash asset: ${asset.uri}", e2)
                        }
                    }
                }
            }
        }
    }

    fun trashSingleAsset(asset: LocalAsset) {
        // Optimistically remove from state in memory so UI updates instantly
        _uiState.update { current ->
            val updatedContents = current.folderContents.mapValues { (_, items) ->
                items.filter { !(it is GridItem.Asset && it.asset.uri == asset.uri) }
            }
            current.copy(folderContents = updatedContents)
        }
        viewModelScope.launch {
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
            container.syncPrefs
        ) as T
}
