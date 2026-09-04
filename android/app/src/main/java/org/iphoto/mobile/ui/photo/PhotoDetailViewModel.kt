package org.iphoto.mobile.ui.photo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.iphoto.mobile.model.Photo
import org.iphoto.mobile.network.PhotoApi

sealed interface PhotoDetailUiState {
    data object Loading : PhotoDetailUiState
    data class Loaded(val photo: Photo) : PhotoDetailUiState
    data class Failed(val message: String) : PhotoDetailUiState
}

class PhotoDetailViewModel(
    private val photoApi: PhotoApi,
    private val photoId: String
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
}

class PhotoDetailViewModelFactory(
    private val photoApi: PhotoApi,
    private val photoId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PhotoDetailViewModel(photoApi, photoId) as T
}
