package org.iphoto.mobile.ui.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
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
import org.iphoto.mobile.sync.SyncDiff
import org.iphoto.mobile.sync.SyncRepository
import org.iphoto.mobile.sync.SyncWorker

private const val TAG = "SyncViewModel"

data class SyncUiState(
    val spaces: List<SpaceDTO> = emptyList(),
    val selectedSpaceId: String? = null,
    val folderUri: String? = null,
    val folderDisplayName: String? = null,
    val lastSyncAt: Long = 0L,
    val autoSyncEnabled: Boolean = false,
    val isLoadingSpaces: Boolean = true,
    val isPreviewing: Boolean = false,
    val isSyncRunning: Boolean = false,
    val pendingDiff: SyncDiff? = null,
    // Photos excluded from the currently synced Space (SpaceDetailScreen's "Exclude from sync"
    // action) - shown here so the user has somewhere to undo it, since an excluded photo is
    // garbaged and no longer visible in the Space's own photo grid.
    val excludedAssets: List<TrackedAsset> = emptyList(),
    val error: String? = null
)

class SyncViewModel(
    private val appContext: Context,
    private val spaceApi: SpaceApi,
    private val photoApi: PhotoApi,
    private val syncRepository: SyncRepository,
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
                .getWorkInfosForUniqueWorkFlow(SyncWorker.UNIQUE_WORK_NAME)
                .collect { infos ->
                    // ENQUEUED counts as "running" here because a one-time request only sits
                    // ENQUEUED briefly, right before it runs.
                    manualSyncRunning = infos.any {
                        it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                    }
                    updateRunningState()
                }
        }
        viewModelScope.launch {
            WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWorkFlow(SyncWorker.PERIODIC_WORK_NAME)
                .collect { infos ->
                    // Unlike the one-time work above, periodic work sits ENQUEUED between runs as
                    // its normal steady state - only RUNNING means a sync is actually in progress,
                    // otherwise "Sync now" would look permanently busy once auto-sync is on.
                    autoSyncRunning = infos.any { it.state == WorkInfo.State.RUNNING }
                    updateRunningState()
                }
        }
    }

    private var manualSyncRunning = false
    private var autoSyncRunning = false

    private fun updateRunningState() {
        _uiState.value = _uiState.value.copy(
            isSyncRunning = manualSyncRunning || autoSyncRunning,
            lastSyncAt = syncPrefs.lastSyncAt
        )
    }

    fun selectSpace(spaceId: String) {
        syncPrefs.spaceId = spaceId
        _uiState.value = _uiState.value.copy(selectedSpaceId = spaceId, pendingDiff = null)
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
            folderDisplayName = displayName,
            pendingDiff = null
        )
    }

    fun previewSync() {
        val spaceId = _uiState.value.selectedSpaceId ?: return
        val folderUri = _uiState.value.folderUri ?: return
        _uiState.value = _uiState.value.copy(isPreviewing = true, error = null)
        viewModelScope.launch {
            _uiState.value = try {
                val skipHashes = syncPrefs.excludedHashes + syncPrefs.movedToMainHashes
                val diff = syncRepository.computeDiff(spaceId, Uri.parse(folderUri), skipHashes)
                _uiState.value.copy(isPreviewing = false, pendingDiff = diff)
            } catch (e: Exception) {
                _uiState.value.copy(isPreviewing = false, error = e.message ?: "Failed to compare space with folder")
            }
        }
    }

    fun dismissPreview() {
        _uiState.value = _uiState.value.copy(pendingDiff = null)
    }

    // Opt-in periodic background sync - off by default (SyncPrefs.autoSyncEnabled), matching the
    // feature's original "nothing happens unless you tap the button" design. Requires a space and
    // folder to already be chosen, same precondition as "Sync now".
    fun setAutoSyncEnabled(enabled: Boolean) {
        syncPrefs.autoSyncEnabled = enabled
        _uiState.value = _uiState.value.copy(autoSyncEnabled = enabled)
        if (enabled) {
            SyncWorker.schedulePeriodic(appContext)
        } else {
            SyncWorker.cancelPeriodic(appContext)
        }
    }

    fun confirmSync() {
        Log.d(TAG, "Sync requested by user for space ${_uiState.value.selectedSpaceId} - see SyncWorker logs for progress")
        _uiState.value = _uiState.value.copy(pendingDiff = null)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(SyncWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}

class SyncViewModelFactory(
    private val appContext: Context,
    private val spaceApi: SpaceApi,
    private val photoApi: PhotoApi,
    private val syncRepository: SyncRepository,
    private val syncPrefs: SyncPrefs
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SyncViewModel(appContext, spaceApi, photoApi, syncRepository, syncPrefs) as T
}
