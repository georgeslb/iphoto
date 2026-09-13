package org.iphoto.mobile.ui.sync

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.iphoto.mobile.data.SyncPrefs
import org.iphoto.mobile.data.SyncPrefs.TrackedAsset
import org.iphoto.mobile.model.SpaceDTO
import org.iphoto.mobile.network.PhotoApi
import org.iphoto.mobile.network.SpaceApi
import org.iphoto.mobile.sync.SyncWorker

data class SyncUiState(
    val spaces: List<SpaceDTO> = emptyList(),
    val selectedSpaceId: String? = null,
    val folderUri: String? = null,
    val folderDisplayName: String? = null,
    val lastSyncAt: Long = 0L,
    val autoSyncEnabled: Boolean = false,
    val isLoadingSpaces: Boolean = true,
    val isSyncRunning: Boolean = false,
    // Photos excluded from the currently synced Space (SpaceDetailScreen's "Exclude from sync"
    // action) - shown here so the user has somewhere to undo it, since an excluded photo is
    // garbaged and no longer visible in the Space's own photo grid.
    val excludedAssets: List<TrackedAsset> = emptyList(),
    val error: String? = null
)

/**
 * There is no manual "Sync now" trigger any more - a one-off tap could push an unbounded number
 * of assets in a single burst. The only way to sync is the opt-in periodic background job
 * (schedulePeriodic/cancelPeriodic below), which SyncWorker itself now also caps to a byte budget
 * per run (see SyncRepository.selectUploadBatch) rather than draining the whole backlog at once.
 */
class SyncViewModel(
    private val appContext: Context,
    private val spaceApi: SpaceApi,
    private val photoApi: PhotoApi,
    private val syncPrefs: SyncPrefs
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SyncUiState(
            selectedSpaceId = syncPrefs.spaceId,
            folderUri = syncPrefs.folderUri,
            folderDisplayName = syncPrefs.folderDisplayName,
            lastSyncAt = syncPrefs.lastSyncAt,
            autoSyncEnabled = syncPrefs.autoSyncEnabled,
            excludedAssets = syncPrefs.excludedAssets.toList()
        )
    )
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    init {
        loadSpaces()
        observeWork()
    }

    private fun loadSpaces() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingSpaces = true, error = null)
            _uiState.value = try {
                // Every Space - including Main - is a valid sync target: Main is just the Space
                // the backend never lets the user delete (SpaceDTO.deletionAllowed = false), which
                // has no bearing on whether its own photos can mirror a device folder.
                val syncable = spaceApi.list()
                _uiState.value.copy(spaces = syncable, isLoadingSpaces = false)
            } catch (e: Exception) {
                _uiState.value.copy(isLoadingSpaces = false, error = e.message ?: "Failed to load spaces")
            }
        }
    }

    // Reverses SpaceDetailScreen's "Exclude from sync" action: restores the photo in the Space and
    // stops treating its hash as excluded, so the next sync pass (or this call, immediately) picks
    // the still-present local file back up normally.
    fun includeAsset(asset: TrackedAsset) {
        viewModelScope.launch {
            try {
                photoApi.restoreFromGarbage(listOf(asset.photoId))
                syncPrefs.includeAsset(asset.hash)
                _uiState.value = _uiState.value.copy(excludedAssets = syncPrefs.excludedAssets.toList())
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Failed to include \"${asset.title}\"")
            }
        }
    }

    private fun observeWork() {
        viewModelScope.launch {
            WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWorkFlow(SyncWorker.PERIODIC_WORK_NAME)
                .collect { infos ->
                    // Periodic work sits ENQUEUED between runs as its normal steady state - only
                    // RUNNING means a sync is actually in progress right now.
                    _uiState.value = _uiState.value.copy(
                        isSyncRunning = infos.any { it.state == WorkInfo.State.RUNNING },
                        lastSyncAt = syncPrefs.lastSyncAt
                    )
                }
        }
    }

    fun selectSpace(spaceId: String) {
        syncPrefs.spaceId = spaceId
        _uiState.value = _uiState.value.copy(selectedSpaceId = spaceId)
    }

    fun selectFolder(uri: Uri, displayName: String) {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        syncPrefs.folderUri = uri.toString()
        syncPrefs.folderDisplayName = displayName
        _uiState.value = _uiState.value.copy(
            folderUri = uri.toString(),
            folderDisplayName = displayName
        )
        // If auto-sync is already on, the content-uri trigger (if any) is still watching the
        // *previous* folder - re-arm it for the new one rather than waiting for the next periodic
        // run (up to 15 minutes away) to notice the change.
        if (syncPrefs.autoSyncEnabled) {
            SyncWorker.scheduleContentTrigger(appContext, uri)
        }
    }

    // Opt-in background sync - off by default (SyncPrefs.autoSyncEnabled). Requires a space and
    // folder to already be chosen. This is now the *only* way a sync ever runs (see this class's
    // own kdoc for why the old one-off "Sync now" trigger was removed), driven by two triggers -
    // see SyncWorker's own kdoc for the near-instant content-uri trigger vs. the ~15-minute
    // periodic fallback.
    fun setAutoSyncEnabled(enabled: Boolean) {
        syncPrefs.autoSyncEnabled = enabled
        _uiState.value = _uiState.value.copy(autoSyncEnabled = enabled)
        if (enabled) {
            SyncWorker.schedulePeriodic(appContext)
            syncPrefs.folderUri?.let { SyncWorker.scheduleContentTrigger(appContext, Uri.parse(it)) }
        } else {
            SyncWorker.cancelPeriodic(appContext)
        }
    }
}

class SyncViewModelFactory(
    private val appContext: Context,
    private val spaceApi: SpaceApi,
    private val photoApi: PhotoApi,
    private val syncPrefs: SyncPrefs
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SyncViewModel(appContext, spaceApi, photoApi, syncPrefs) as T
}
