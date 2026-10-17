package org.iphoto.mobile.ui.photo

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

private const val TAG = "PhotoDetailVM"

sealed interface PhotoDetailUiState {
    data object Loading : PhotoDetailUiState
    data class Loaded(
        val photo: Photo,
        val isMutating: Boolean = false,
        val actionError: String? = null
    ) : PhotoDetailUiState
    data class Failed(val message: String) : PhotoDetailUiState
    data object Removed : PhotoDetailUiState
}

class PhotoDetailViewModel(
    private val photoApi: PhotoApi,
    private val photoId: String,
    private val albumId: String? = null,
    val canMoveToMain: Boolean = false,
    initialPhoto: Photo? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow<PhotoDetailUiState>(
        if (initialPhoto != null) PhotoDetailUiState.Loaded(initialPhoto) else PhotoDetailUiState.Loading
    )
    val uiState: StateFlow<PhotoDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val fetched = photoApi.get(photoId)
                val currentLoaded = _uiState.value as? PhotoDetailUiState.Loaded
                _uiState.value = if (currentLoaded != null) {
                    currentLoaded.copy(photo = fetched)
                } else {
                    PhotoDetailUiState.Loaded(fetched)
                }
            } catch (e: Exception) {
                if (_uiState.value is PhotoDetailUiState.Loading) {
                    _uiState.value = PhotoDetailUiState.Failed(e.message ?: "Failed to load photo")
                }
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
                _uiState.value = PhotoDetailUiState.Removed
            } catch (e: Exception) {
                _uiState.value = loaded.copy(isMutating = false, actionError = e.message ?: "Failed to move to garbage")
            }
        }
    }

    fun moveToMain(photoApi: PhotoApi) = withLoaded { loaded ->
        _uiState.value = loaded.copy(isMutating = true, actionError = null)
        viewModelScope.launch {
            try {
                photoApi.moveToMain(listOf(photoId))
                _uiState.value = PhotoDetailUiState.Removed
            } catch (e: Exception) {
                _uiState.value = loaded.copy(isMutating = false, actionError = e.message ?: "Failed to move to Main Space")
            }
        }
    }

    fun removeFromAlbum(albumApi: AlbumApi, onAlbumsChanged: () -> Unit = {}) = withLoaded { loaded ->
        val id = albumId ?: return@withLoaded
        _uiState.value = loaded.copy(isMutating = true, actionError = null)
        viewModelScope.launch {
            try {
                albumApi.removePhotos(id, AlbumPhotoRequest(listOf(photoId)))
                onAlbumsChanged()
                _uiState.value = PhotoDetailUiState.Removed
            } catch (e: Exception) {
                _uiState.value = loaded.copy(isMutating = false, actionError = e.message ?: "Failed to remove from album")
            }
        }
    }

    fun addToAlbum(albumApi: AlbumApi, albumId: String, onAlbumsChanged: () -> Unit = {}) = withLoaded { loaded ->
        _uiState.value = loaded.copy(isMutating = true, actionError = null)
        viewModelScope.launch {
            try {
                albumApi.addPhotos(albumId, AlbumPhotoRequest(listOf(photoId)))
                onAlbumsChanged()
                _uiState.value = loaded.copy(isMutating = false)
            } catch (e: Exception) {
                _uiState.value = loaded.copy(isMutating = false, actionError = e.message ?: "Failed to add to album")
            }
        }
    }

    fun createAlbumAndAdd(albumApi: AlbumApi, albumName: String, onAlbumsChanged: () -> Unit = {}) = withLoaded { loaded ->
        _uiState.value = loaded.copy(isMutating = true, actionError = null)
        viewModelScope.launch {
            try {
                val album = albumApi.create(AlbumCreateRequest(name = albumName))
                albumApi.addPhotos(album.id, AlbumPhotoRequest(listOf(photoId)))
                onAlbumsChanged()
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
    private val canMoveToMain: Boolean = false,
    private val initialPhoto: Photo? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PhotoDetailViewModel(photoApi, photoId, albumId, canMoveToMain, initialPhoto) as T
}
