package org.iphoto.mobile.ui.photo

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

private const val TAG = "PhotoDetailViewModel"

sealed interface PhotoDetailUiState {
    data object Loading : PhotoDetailUiState
    data class Loaded(val photo: Photo, val isMutating: Boolean = false, val actionError: String? = null) :
        PhotoDetailUiState
    data class Failed(val message: String) : PhotoDetailUiState
    // Only reachable from Loaded, once moveToGarbage or removeFromAlbum succeeds - either way the
    // photo is no longer visible from wherever the lightbox was opened, so the screen navigates
    // back on it (see PhotoDetailScreen's LaunchedEffect).
    data object Removed : PhotoDetailUiState
}

class PhotoDetailViewModel(
    private val photoApi: PhotoApi,
    private val photoId: String,
    // Set only when the lightbox was opened from AlbumDetailScreen - lets it offer "remove from
    // album" alongside the garbage/album/date actions that apply everywhere.
    private val albumId: String? = null,
    // Set only when the lightbox was opened from the Space currently synced to a device folder
    // (see SyncPrefs) - mirrors "move to garbage" onto the matching local file right away, same as
    // PagedPhotoGridViewModel.onPhotosMovedToGarbage does for the grid's own selection action.
    private val onPhotoMovedToGarbage: (suspend (Photo) -> Unit)? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow<PhotoDetailUiState>(PhotoDetailUiState.Loading)
    val uiState: StateFlow<PhotoDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = try {
                PhotoDetailUiState.Loaded(photoApi.get(photoId))
            } catch (e: Exception) {
                PhotoDetailUiState.Failed(e.message ?: "Failed to load photo")
            }
        }
    }

    private fun withLoaded(block: (PhotoDetailUiState.Loaded) -> Unit) {
        (_uiState.value as? PhotoDetailUiState.Loaded)?.let(block)
    }

    fun moveToGarbage() = withLoaded { loaded ->
        _uiState.value = loaded.copy(isMutating = true, actionError = null)
        viewModelScope.launch {
            try {
                photoApi.moveToGarbage(listOf(photoId))
                // Best-effort: if this throws, the photo is still garbaged server-side, and the
                // next sync pass will reconcile the local file via computeDiff's toRemove.
                try {
                    onPhotoMovedToGarbage?.invoke(loaded.photo)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to mirror move-to-garbage onto the local synced folder", e)
                }
                _uiState.value = PhotoDetailUiState.Removed
            } catch (e: Exception) {
                _uiState.value = loaded.copy(isMutating = false, actionError = e.message ?: "Failed to move to garbage")
            }
        }
    }

    // Only meaningful when albumId is set (lightbox opened from AlbumDetailScreen) - removing a
    // reference from this album doesn't touch the photo's Main Space ownership, but the photo is
    // no longer part of *this* album view, so the screen navigates back the same way moveToGarbage
    // does.
    fun removeFromAlbum(albumApi: AlbumApi) = withLoaded { loaded ->
        val id = albumId ?: return@withLoaded
        _uiState.value = loaded.copy(isMutating = true, actionError = null)
        viewModelScope.launch {
            try {
                albumApi.removePhotos(id, AlbumPhotoRequest(listOf(photoId)))
                _uiState.value = PhotoDetailUiState.Removed
            } catch (e: Exception) {
                _uiState.value = loaded.copy(isMutating = false, actionError = e.message ?: "Failed to remove from album")
            }
        }
    }

    fun addToAlbum(albumApi: AlbumApi, albumId: String) = withLoaded { loaded ->
        _uiState.value = loaded.copy(isMutating = true, actionError = null)
        viewModelScope.launch {
            try {
                albumApi.addPhotos(albumId, AlbumPhotoRequest(listOf(photoId)))
                _uiState.value = loaded.copy(isMutating = false)
            } catch (e: Exception) {
                _uiState.value = loaded.copy(isMutating = false, actionError = e.message ?: "Failed to add to album")
            }
        }
    }

    fun createAlbumAndAdd(albumApi: AlbumApi, albumName: String) = withLoaded { loaded ->
        _uiState.value = loaded.copy(isMutating = true, actionError = null)
        viewModelScope.launch {
            try {
                val album = albumApi.create(AlbumCreateRequest(name = albumName))
                albumApi.addPhotos(album.id, AlbumPhotoRequest(listOf(photoId)))
                _uiState.value = loaded.copy(isMutating = false)
            } catch (e: Exception) {
                _uiState.value = loaded.copy(isMutating = false, actionError = e.message ?: "Failed to create album")
            }
        }
    }

    fun updateDate(newDate: LocalDateTime) = withLoaded { loaded ->
        _uiState.value = loaded.copy(isMutating = true, actionError = null)
        viewModelScope.launch {
            try {
                val formatted = newDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                val updated = photoApi.updateDate(photoId, UpdatePhotoDateRequest(formatted))
                _uiState.value = PhotoDetailUiState.Loaded(updated)
            } catch (e: Exception) {
                _uiState.value = loaded.copy(isMutating = false, actionError = e.message ?: "Failed to update date")
            }
        }
    }

    fun dismissActionError() = withLoaded { loaded ->
        _uiState.value = loaded.copy(actionError = null)
    }
}

class PhotoDetailViewModelFactory(
    private val photoApi: PhotoApi,
    private val photoId: String,
    private val albumId: String? = null,
    private val onPhotoMovedToGarbage: (suspend (Photo) -> Unit)? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PhotoDetailViewModel(photoApi, photoId, albumId, onPhotoMovedToGarbage) as T
}
