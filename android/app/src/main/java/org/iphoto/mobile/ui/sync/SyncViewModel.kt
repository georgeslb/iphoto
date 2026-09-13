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
import org.iphoto.mobile.model.SpaceDTO
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
    val isLoadingSpaces: Boolean = true,
    val isPreviewing: Boolean = false,
    val isSyncRunning: Boolean = false,
    val pendingDiff: SyncDiff? = null,
    val error: String? = null
)

class SyncViewModel(
    private val appContext: Context,
    private val spaceApi: SpaceApi,
    private val syncRepository: SyncRepository,
    private val syncPrefs: SyncPrefs
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SyncUiState(
            selectedSpaceId = syncPrefs.spaceId,
            folderUri = syncPrefs.folderUri,
            folderDisplayName = syncPrefs.folderDisplayName,
            lastSyncAt = syncPrefs.lastSyncAt
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
                // The Main space isn't a valid sync target - it's the app's own default landing
                // space, not a user-created collection meant to mirror one phone folder. The
                // backend already flags it this way via `deletionAllowed = false`.
                val syncable = spaceApi.list().filter { it.deletionAllowed }
                _uiState.value.copy(spaces = syncable, isLoadingSpaces = false)
            } catch (e: Exception) {
                _uiState.value.copy(isLoadingSpaces = false, error = e.message ?: "Failed to load spaces")
            }
        }
    }

    private fun observeWork() {
        viewModelScope.launch {
            WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWorkFlow(SyncWorker.UNIQUE_WORK_NAME)
                .collect { infos ->
                    val running = infos.any {
                        it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                    }
                    _uiState.value = _uiState.value.copy(
                        isSyncRunning = running,
                        lastSyncAt = syncPrefs.lastSyncAt
                    )
                }
        }
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
                val diff = syncRepository.computeDiff(spaceId, Uri.parse(folderUri))
                _uiState.value.copy(isPreviewing = false, pendingDiff = diff)
            } catch (e: Exception) {
                _uiState.value.copy(isPreviewing = false, error = e.message ?: "Failed to compare space with folder")
            }
        }
    }

    fun dismissPreview() {
        _uiState.value = _uiState.value.copy(pendingDiff = null)
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
    private val syncRepository: SyncRepository,
    private val syncPrefs: SyncPrefs
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SyncViewModel(appContext, spaceApi, syncRepository, syncPrefs) as T
}
