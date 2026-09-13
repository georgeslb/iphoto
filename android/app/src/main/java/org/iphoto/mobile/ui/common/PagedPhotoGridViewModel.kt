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
    private val onPhotosMovedToGarbage: (suspend (List<Photo>) -> Unit)? = null
) : ViewModel() {

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
                // Best-effort: if this throws, the Space photo is still garbaged server-side, and
                // the next sync pass will reconcile the local file via computeDiff's toRemove.
                try {
                    onPhotosMovedToGarbage?.invoke(selectedPhotos)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to mirror move-to-garbage onto the local synced folder", e)
                }
                loadFirstPage()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isMutating = false,
                    actionError = e.message ?: "Failed to move to garbage"
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
    private val onPhotosMovedToGarbage: (suspend (List<Photo>) -> Unit)? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PagedPhotoGridViewModel(loadPage, albumId, onPhotosMovedToGarbage) as T
}
