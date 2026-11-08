package org.iphoto.mobile.ui.photo

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
    initialPhotoId: String,
    private val albumId: String? = null,
    val canMoveToMain: Boolean = false,
    initialPhoto: Photo? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    var currentPhotoId: String = initialPhotoId
        private set

    private val _uiState = MutableStateFlow<PhotoDetailUiState>(
        if (initialPhoto != null) PhotoDetailUiState.Loaded(initialPhoto) else PhotoDetailUiState.Loading
    )
    val uiState: StateFlow<PhotoDetailUiState> = _uiState.asStateFlow()

    init {
        org.iphoto.mobile.ui.common.PhotoBrowsingContext.lastViewedPhotoId = currentPhotoId
        viewModelScope.launch {
            try {
                val fetched = photoApi.get(currentPhotoId)
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

    fun moveToGarbage(onLibraryChanged: () -> Unit = {}) = withLoaded { loaded ->
        _uiState.value = loaded.copy(isMutating = true, actionError = null)
        viewModelScope.launch {
            try {
                photoApi.moveToGarbage(listOf(currentPhotoId))
                org.iphoto.mobile.ui.common.PhotoBrowsingContext.removePhoto(currentPhotoId)
                onLibraryChanged()
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
                photoApi.moveToMain(listOf(currentPhotoId))
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
                albumApi.removePhotos(id, AlbumPhotoRequest(listOf(currentPhotoId)))
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
                albumApi.addPhotos(albumId, AlbumPhotoRequest(listOf(currentPhotoId)))
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
                albumApi.addPhotos(album.id, AlbumPhotoRequest(listOf(currentPhotoId)))
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
                val updated = photoApi.updateDate(currentPhotoId, UpdatePhotoDateRequest(formatted))
                _uiState.value = PhotoDetailUiState.Loaded(updated)
            } catch (e: Exception) {
                _uiState.value = loaded.copy(isMutating = false, actionError = e.message ?: "Failed to update date")
            }
        }
    }

    suspend fun downloadOriginalToUri(contentResolver: ContentResolver, targetUri: Uri) = withContext(ioDispatcher) {
        val responseBody = photoApi.downloadOriginal(currentPhotoId)
        responseBody.byteStream().use { input ->
            contentResolver.openOutputStream(targetUri)?.use { output ->
                input.copyTo(output)
            } ?: throw java.io.IOException("Could not open output stream for $targetUri")
        }
    }

    suspend fun downloadOriginalToCache(cacheFile: File) = withContext(ioDispatcher) {
        val responseBody = photoApi.downloadOriginal(currentPhotoId)
        cacheFile.parentFile?.mkdirs()
        responseBody.byteStream().use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    fun saveEditedPhoto(
        contentResolver: ContentResolver,
        sourceUri: Uri,
        saveAsNew: Boolean,
        onSuccess: (Photo) -> Unit
    ) = withLoaded { loaded ->
        _uiState.value = loaded.copy(isMutating = true, actionError = null)
        viewModelScope.launch {
            try {
                val mime = loaded.photo.contentType ?: "image/jpeg"
                val bytes = withContext(ioDispatcher) {
                    contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                        ?: throw java.io.IOException("Could not read bytes from $sourceUri")
                }
                val requestFile = bytes.toRequestBody(mime.toMediaTypeOrNull())
                val ext = loaded.photo.title.substringAfterLast('.', "").ifBlank { "jpg" }
                val filename = "edit_${loaded.photo.id}.$ext"
                val multipart = MultipartBody.Part.createFormData("file", filename, requestFile)
                val updated = photoApi.editPhoto(currentPhotoId, multipart, saveAsNew)
                currentPhotoId = updated.id
                _uiState.value = PhotoDetailUiState.Loaded(updated)
                onSuccess(updated)
            } catch (e: Exception) {
                _uiState.value = loaded.copy(
                    isMutating = false,
                    actionError = e.message ?: "Failed to save edited photo"
                )
            }
        }
    }

    fun saveEditedPhoto(
        file: File,
        saveAsNew: Boolean,
        onSuccess: (Photo) -> Unit
    ) = withLoaded { loaded ->
        _uiState.value = loaded.copy(isMutating = true, actionError = null)
        viewModelScope.launch {
            try {
                val mime = loaded.photo.contentType ?: "image/jpeg"
                val requestFile = file.asRequestBody(mime.toMediaTypeOrNull())
                val multipart = MultipartBody.Part.createFormData("file", file.name, requestFile)
                val updated = photoApi.editPhoto(currentPhotoId, multipart, saveAsNew)
                currentPhotoId = updated.id
                _uiState.value = PhotoDetailUiState.Loaded(updated)
                onSuccess(updated)
            } catch (e: Exception) {
                _uiState.value = loaded.copy(
                    isMutating = false,
                    actionError = e.message ?: "Failed to save edited photo"
                )
            } finally {
                file.delete()
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
