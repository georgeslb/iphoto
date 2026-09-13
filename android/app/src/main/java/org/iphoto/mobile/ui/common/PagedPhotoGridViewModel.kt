package org.iphoto.mobile.ui.common

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.iphoto.mobile.model.AlbumCreateRequest
import org.iphoto.mobile.model.AlbumPhotoRequest
import org.iphoto.mobile.model.Photo
import org.iphoto.mobile.model.UpdatePhotoDateRequest
import org.iphoto.mobile.network.AlbumApi
import org.iphoto.mobile.network.PhotoApi

private const val TAG = "PagedPhotoGridVM"

data class PagedPhotosUiState(
    val photos: List<Photo> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val endReached: Boolean = false,
    // Google Photos-style long-press multi-select, shared by every screen built on this
    // ViewModel (Library/Album-detail/Space-detail) - see PhotoGridContent/PhotoThumbnail.
    val selectedIds: Set<String> = emptySet(),
    val isMutating: Boolean = false,
    val actionError: String? = null
) {
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
}

/**
 * Generic pager shared by Library/Album-detail/Space-detail - GET /api/photos, GET
 * /api/albums/{id}/photos and GET /api/spaces/{id}/photos all page a list of Photo, just with two
 * different JSON envelopes (see PhotoPageResponse vs SpringPage). [loadPage] adapts whichever
 * envelope the caller's endpoint uses down to (items, isLastPage).
 */
class PagedPhotoGridViewModel(
    private val loadPage: suspend (page: Int, size: Int) -> Pair<List<Photo>, Boolean>,
    // Set only by AlbumDetailScreen - lets PhotoGridContent offer "remove from album" alongside
    // the garbage/album/date actions that apply everywhere else too.
    val albumId: String? = null,
    // Set only by SpaceDetailScreen when its Space is the one currently synced to a device folder
    // (see SyncPrefs) - mirrors a "move to garbage" onto the matching local file (SyncRepository's
    // LocalTrash) right away, instead of waiting for the next sync pass to notice the Space photo
    // is gone.
    private val onPhotosMovedToGarbage: (suspend (List<Photo>) -> Unit)? = null,
    // Also only set for the currently synced Space - records each excluded photo's hash (see
    // SyncPrefs.excludedAssets) so a later sync leaves its still-present local file alone, rather
    // than mirroring the removal onto the phone the way onPhotosMovedToGarbage does. Exposed via
    // canExcludeFromSync so PhotoGridContent only offers the action where it's meaningful.
    private val onPhotosExcludedFromSync: (suspend (List<Photo>) -> Unit)? = null,
    // Set only by SpaceDetailScreen (any Space, synced or not - "move to Main Space" is a general
    // Space feature, see specification.md) so PhotoGridContent offers the action there and nowhere
    // else (moving a Library/Album photo to Main is meaningless - it's either already there, or, for
    // an album, only ever references a Main Space photo to begin with).
    val canMoveToMain: Boolean = false,
    // Only set (in addition to canMoveToMain) for the currently synced Space - records each moved
    // photo's hash (see SyncPrefs.movedToMainAssets) for the same reason onPhotosExcludedFromSync
    // does: without it, the next sync pass would see the still-present local file, find its hash no
    // longer active in *this* Space, and re-upload it as if new - duplicating a photo that now just
    // lives in Main instead.
    private val onPhotosMovedToMain: (suspend (List<Photo>) -> Unit)? = null
) : ViewModel() {

    val canExcludeFromSync: Boolean get() = onPhotosExcludedFromSync != null

    private val _uiState = MutableStateFlow(PagedPhotosUiState())
    val uiState: StateFlow<PagedPhotosUiState> = _uiState.asStateFlow()

    private var nextPage = 0

    init {
        loadFirstPage()
    }

    fun loadFirstPage() {
        nextPage = 0
        _uiState.value = PagedPhotosUiState(isLoading = true)
        viewModelScope.launch {
            _uiState.value = try {
                val (photos, last) = loadPage(0, PAGE_SIZE)
                nextPage = 1
                PagedPhotosUiState(photos = photos, isLoading = false, endReached = last)
            } catch (e: Exception) {
                PagedPhotosUiState(isLoading = false, error = e.message ?: "Failed to load photos")
            }
        }
    }

    // Re-fetches page 0 and swaps in the result without flipping isLoading or touching
    // selectedIds/isMutating/actionError - a plain loadFirstPage() would blank the grid and show a
    // spinner, which is fine for an explicit user action but not for a refresh firing repeatedly
    // in the background (SpaceDetailScreen uses this both on screen resume and, while a sync is
    // actively running against the currently-viewed Space, on a timer - so new thumbnails show up
    // without the user needing to leave and reopen the screen). A refresh already in flight, or a
    // real load still in progress, is left alone rather than piling up a second one.
    private var isRefreshing = false

    fun refreshQuietly() {
        if (_uiState.value.isLoading || isRefreshing) return
        isRefreshing = true
        viewModelScope.launch {
            try {
                val (photos, last) = loadPage(0, PAGE_SIZE)
                nextPage = 1
                _uiState.value = _uiState.value.copy(photos = photos, endReached = last)
            } catch (e: Exception) {
                // Silent by design - this is a background refresh, not a user-initiated action.
                // Whatever the last real load/error showed stays on screen.
            } finally {
                isRefreshing = false
            }
        }
    }

    fun loadMore() {
        val current = _uiState.value
        if (current.isLoading || current.isLoadingMore || current.endReached) return
        _uiState.value = current.copy(isLoadingMore = true)
        viewModelScope.launch {
            try {
                val (photos, last) = loadPage(nextPage, PAGE_SIZE)
                nextPage += 1
                _uiState.value = _uiState.value.copy(
                    photos = _uiState.value.photos + photos,
                    isLoadingMore = false,
                    endReached = last,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingMore = false, error = e.message)
            }
        }
    }

    fun toggleSelection(photoId: String) {
        val current = _uiState.value.selectedIds
        _uiState.value = _uiState.value.copy(
            selectedIds = if (photoId in current) current - photoId else current + photoId
        )
    }

    // Tapping a day header (PhotoGridContent) selects/deselects every photo in that day at once -
    // "already all selected" toggles off, otherwise it's a no-op union so partially-selected days
    // just top up to fully selected, matching stock Photos app behavior.
    fun toggleDaySelection(dayPhotoIds: List<String>) {
        val current = _uiState.value.selectedIds
        val allSelected = dayPhotoIds.isNotEmpty() && current.containsAll(dayPhotoIds)
        _uiState.value = _uiState.value.copy(
            selectedIds = if (allSelected) current - dayPhotoIds.toSet() else current + dayPhotoIds
        )
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedIds = emptySet())
    }

    // Garbage-d photos disappear from every Main/Space view, so a full reload (rather than an
    // optimistic local removal) is the simplest way to keep pagination/day-grouping consistent.
    fun moveSelectionToGarbage(photoApi: PhotoApi) {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        val selectedPhotos = _uiState.value.photos.filter { it.id in _uiState.value.selectedIds }
        _uiState.value = _uiState.value.copy(isMutating = true, actionError = null)
        viewModelScope.launch {
            try {
                photoApi.moveToGarbage(ids)
                mirrorToLocalTrashInBackground(selectedPhotos)
                loadFirstPage()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isMutating = false,
                    actionError = e.message ?: "Failed to move to garbage"
                )
            }
        }
    }

    // onPhotosMovedToGarbage (SyncRepository.trashLocalFilesForHashes) re-scans and re-hashes the
    // *entire* synced folder via SAF to find the handful of files matching this selection - slow
    // enough on a large folder that awaiting it inline made "move to garbage"/"move to Main + also
    // remove from device" visibly hang before the action appeared to finish. It was already
    // documented as best-effort (a failure here doesn't undo the server-side move, the next sync
    // pass reconciles it), so there's no correctness reason to block on it - launched as its own
    // independent coroutine instead, so the grid refreshes and the mutating spinner clears the
    // moment the server call succeeds, while this keeps running in the background.
    private fun mirrorToLocalTrashInBackground(photos: List<Photo>) {
        viewModelScope.launch {
            try {
                onPhotosMovedToGarbage?.invoke(photos)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to mirror move-to-garbage onto the local synced folder", e)
            }
        }
    }

    // Removes the selection from this Space (garbaged server-side, same as moveSelectionToGarbage)
    // without mirroring anything onto the phone - the local file stays exactly where it is.
    // Distinct from moveSelectionToGarbage: that action's local-trash mirror means "this file is
    // gone", while excluding means "keep the file, just stop syncing it into this Space".
    fun excludeSelectionFromSync(photoApi: PhotoApi) {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        val selectedPhotos = _uiState.value.photos.filter { it.id in _uiState.value.selectedIds }
        _uiState.value = _uiState.value.copy(isMutating = true, actionError = null)
        viewModelScope.launch {
            try {
                photoApi.moveToGarbage(ids)
                onPhotosExcludedFromSync?.invoke(selectedPhotos)
                loadFirstPage()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isMutating = false,
                    actionError = e.message ?: "Failed to exclude from sync"
                )
            }
        }
    }

    // Moves the selection to the Main Space (Albums hold references to Main Space photos, Spaces
    // own them - this actually transfers ownership, unlike addSelectionToAlbum below). The photos
    // leave this Space entirely, so this reloads the same way moveSelectionToGarbage does.
    //
    // alsoRemoveFromDevice: the user's answer to "also remove this from your phone?" (asked by
    // PhotoGridContent only when canExcludeFromSync - i.e. this Space is the one currently synced
    // to a device folder, the only case where a matching local file could even exist). When true,
    // this reuses onPhotosMovedToGarbage - the exact same "move this file to .iPhotoTrash" mirror
    // moveSelectionToGarbage uses - since it's the same underlying operation either way.
    fun moveSelectionToMain(photoApi: PhotoApi, alsoRemoveFromDevice: Boolean = false) {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        val selectedPhotos = _uiState.value.photos.filter { it.id in _uiState.value.selectedIds }
        _uiState.value = _uiState.value.copy(isMutating = true, actionError = null)
        viewModelScope.launch {
            try {
                photoApi.moveToMain(ids)
                // Best-effort, same reasoning as moveSelectionToGarbage's mirror: if this throws,
                // the photo is still moved server-side, and the next sync pass would just re-upload
                // the local file once more before settling - not silent data loss, just a wasted
                // upload. Bookkeeping (markMovedToMain) is cheap/synchronous so it stays inline;
                // the actual device-file removal is the slow full-folder scan+hash, so it's
                // launched independently (see mirrorToLocalTrashInBackground) rather than making
                // the whole action wait on it.
                try {
                    onPhotosMovedToMain?.invoke(selectedPhotos)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to record move-to-Main for the synced folder", e)
                }
                if (alsoRemoveFromDevice) {
                    mirrorToLocalTrashInBackground(selectedPhotos)
                }
                loadFirstPage()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isMutating = false,
                    actionError = e.message ?: "Failed to move to Main Space"
                )
            }
        }
    }

    // Adding to an album only creates references (Albums hold references to Main Space photos,
    // they don't own them) - the photos stay exactly where they are in Library/Space, so no
    // reload is needed here, just clearing the selection.
    fun addSelectionToAlbum(albumApi: AlbumApi, albumId: String) {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        _uiState.value = _uiState.value.copy(isMutating = true, actionError = null)
        viewModelScope.launch {
            try {
                albumApi.addPhotos(albumId, AlbumPhotoRequest(ids))
                _uiState.value = _uiState.value.copy(isMutating = false, selectedIds = emptySet())
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isMutating = false,
                    actionError = e.message ?: "Failed to add to album"
                )
            }
        }
    }

    fun createAlbumAndAddSelection(albumApi: AlbumApi, albumName: String) {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        _uiState.value = _uiState.value.copy(isMutating = true, actionError = null)
        viewModelScope.launch {
            try {
                val album = albumApi.create(AlbumCreateRequest(name = albumName))
                albumApi.addPhotos(album.id, AlbumPhotoRequest(ids))
                _uiState.value = _uiState.value.copy(isMutating = false, selectedIds = emptySet())
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isMutating = false,
                    actionError = e.message ?: "Failed to create album"
                )
            }
        }
    }

    // No batch date-update endpoint on the backend - PATCH /api/photos/{id}/date is one photo at
    // a time, so a multi-selection just calls it once per selected id. A changed date can move a
    // photo into a different day group or reorder it within one, so this reloads afterward.
    fun updateSelectionDate(photoApi: PhotoApi, newDate: LocalDateTime) {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        _uiState.value = _uiState.value.copy(isMutating = true, actionError = null)
        viewModelScope.launch {
            try {
                val formatted = newDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                ids.forEach { id -> photoApi.updateDate(id, UpdatePhotoDateRequest(formatted)) }
                loadFirstPage()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isMutating = false,
                    actionError = e.message ?: "Failed to update date"
                )
            }
        }
    }

    // Only meaningful when albumId is set (AlbumDetailScreen) - removing a reference from this
    // album doesn't touch the photo's Main Space ownership, but it does disappear from *this*
    // view, so a reload is needed the same way garbage/date-update need one.
    fun removeSelectionFromAlbum(albumApi: AlbumApi) {
        val id = albumId ?: return
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        _uiState.value = _uiState.value.copy(isMutating = true, actionError = null)
        viewModelScope.launch {
            try {
                albumApi.removePhotos(id, AlbumPhotoRequest(ids))
                loadFirstPage()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isMutating = false,
                    actionError = e.message ?: "Failed to remove from album"
                )
            }
        }
    }

    fun dismissActionError() {
        _uiState.value = _uiState.value.copy(actionError = null)
    }

    companion object {
        private const val PAGE_SIZE = 60
    }
}

class PagedPhotoGridViewModelFactory(
    private val loadPage: suspend (page: Int, size: Int) -> Pair<List<Photo>, Boolean>,
    private val albumId: String? = null,
    private val onPhotosMovedToGarbage: (suspend (List<Photo>) -> Unit)? = null,
    private val onPhotosExcludedFromSync: (suspend (List<Photo>) -> Unit)? = null,
    private val canMoveToMain: Boolean = false,
    private val onPhotosMovedToMain: (suspend (List<Photo>) -> Unit)? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PagedPhotoGridViewModel(
            loadPage,
            albumId,
            onPhotosMovedToGarbage,
            onPhotosExcludedFromSync,
            canMoveToMain,
            onPhotosMovedToMain
        ) as T
}
