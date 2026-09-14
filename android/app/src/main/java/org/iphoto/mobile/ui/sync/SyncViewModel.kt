package org.iphoto.mobile.ui.sync

import android.content.Context
import android.net.Uri
import android.util.Log
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

private const val TAG = "SyncViewModel"

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
    val error: String? = null,
    // Block/unblock-on-sync: true right after detecting, on app start, that the space this
    // device WAS locked to has since been unlocked remotely (web "I lost my phone" escape hatch,
    // or this device's own token being revoked) - see SyncPrefs.lockConfirmed. Drives a one-time
    // warning dialog (SyncScreen) explaining sync was stopped; the pairing has already been
    // cleared by the time this is true, so the form shows empty and ready to be filled in again.
    val syncStoppedRemotely: Boolean = false
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
        checkSyncLockState()
    }

    // Block/unblock-on-sync: runs once per ViewModel creation (app start / re-entering this
    // screen). See SyncPrefs.lockConfirmed's own comment for the two cases this tells apart -
    // never-yet-locked (silently retry) vs. was-locked-but-now-isn't (warn + clear the pairing).
    private fun checkSyncLockState() {
        val spaceId = syncPrefs.spaceId
        if (spaceId == null || syncPrefs.folderUri == null) return // no pairing configured at all

        if (!syncPrefs.lockConfirmed) {
            lockCurrentPairingIfComplete()
            return
        }

        viewModelScope.launch {
            try {
                val space = spaceApi.get(spaceId)
                if (space.syncLocked) {
                    applyUpdatedSpace(space) // still locked (assumed still ours) - keep the UI fresh
                } else {
                    handleSyncStoppedRemotely()
                }
            } catch (e: Exception) {
                // Transient network/auth error - don't clear local state on a guess; the next
                // check (app restart, or another screen visit) gets another chance.
                Log.w(TAG, "Failed to check sync-lock state for space $spaceId", e)
            }
        }
    }

    // The pairing was already cleared server-side (unlocked) by the time we notice - clear it
    // locally too and surface a one-time warning, rather than silently re-locking and letting the
    // user believe sync never stopped.
    private fun handleSyncStoppedRemotely() {
        Log.i(TAG, "Space ${syncPrefs.spaceId} was unlocked remotely - clearing the local sync pairing")
        setAutoSyncEnabled(false)
        syncPrefs.spaceId = null
        syncPrefs.folderUri = null
        syncPrefs.folderDisplayName = null
        syncPrefs.lockConfirmed = false
        _uiState.value = _uiState.value.copy(
            selectedSpaceId = null,
            folderUri = null,
            folderDisplayName = null,
            syncStoppedRemotely = true
        )
    }

    fun dismissSyncStoppedWarning() {
        _uiState.value = _uiState.value.copy(syncStoppedRemotely = false)
    }

    private fun loadSpaces() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingSpaces = true, error = null)
            _uiState.value = try {
                // The Main Space isn't a valid sync target: it's the app's own default landing
                // space (also the destination of Album references and "Move to Main Space"), not a
                // user-created collection meant to mirror one phone folder. deletionAllowed is
                // false for exactly (and only) the Main Space, so it doubles as this check.
                val syncable = spaceApi.list().filter { it.deletionAllowed }
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
        val previousSpaceId = _uiState.value.selectedSpaceId
        syncPrefs.spaceId = spaceId
        _uiState.value = _uiState.value.copy(selectedSpaceId = spaceId)
        // Switching to a different Space means the old one is no longer part of any pairing on
        // this device - free its lock, or it would stay stuck locked with nothing syncing it any
        // more. A no-op if it was never locked (unlockSync tolerates that server-side).
        if (previousSpaceId != null && previousSpaceId != spaceId) {
            unlockSpaceIfLocked(previousSpaceId)
        }
        lockCurrentPairingIfComplete()
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
        lockCurrentPairingIfComplete()
    }

    // Block/unblock-on-sync: a fully-chosen folder+Space pairing IS "sync is active" for this
    // feature (not just while a sync run is actually in flight - see the server's own
    // SpaceService#lockForSync comment), so this locks the moment both halves of the pairing are
    // set. Idempotent server-side for the same device, so calling it from both selectSpace and
    // selectFolder (whichever one happens to complete the pair) is safe either way.
    private fun lockCurrentPairingIfComplete() {
        val spaceId = _uiState.value.selectedSpaceId ?: return
        if (_uiState.value.folderUri == null) return
        viewModelScope.launch {
            try {
                applyUpdatedSpace(spaceApi.lockSync(spaceId))
                syncPrefs.lockConfirmed = true
            } catch (e: Exception) {
                // Most likely 423 (already locked by a different device) or no network - surfaced
                // to the user rather than swallowed, since it means sync won't actually be able to
                // write once it next runs.
                Log.w(TAG, "Failed to lock space $spaceId for sync", e)
                _uiState.value = _uiState.value.copy(error = "Could not lock the space for sync: ${e.message}")
            }
        }
    }

    private fun unlockSpaceIfLocked(spaceId: String) {
        viewModelScope.launch {
            try {
                applyUpdatedSpace(spaceApi.unlockSync(spaceId))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unlock space $spaceId from sync", e)
            }
        }
    }

    private fun applyUpdatedSpace(updated: SpaceDTO) {
        _uiState.value = _uiState.value.copy(
            spaces = _uiState.value.spaces.map { if (it.id == updated.id) updated else it }
        )
    }

    // Explicit "stop sync" - the counterpart to lockCurrentPairingIfComplete: frees the current
    // Space's sync lock and clears the folder<->Space pairing entirely (not just switching to a
    // different one), so nothing on this device claims to be syncing it any more. Also turns off
    // auto-sync, which would otherwise have nothing left to run against.
    fun stopSync() {
        _uiState.value.selectedSpaceId?.let { unlockSpaceIfLocked(it) }
        setAutoSyncEnabled(false)
        syncPrefs.spaceId = null
        syncPrefs.folderUri = null
        syncPrefs.folderDisplayName = null
        // Deliberately stopped by the user on THIS device - not a remote stop, so nothing to warn
        // about later (see checkSyncLockState/SyncPrefs.lockConfirmed's own comment).
        syncPrefs.lockConfirmed = false
        _uiState.value = _uiState.value.copy(
            selectedSpaceId = null,
            folderUri = null,
            folderDisplayName = null
        )
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
