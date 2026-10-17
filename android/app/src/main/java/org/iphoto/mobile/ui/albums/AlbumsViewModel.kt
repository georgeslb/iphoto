package org.iphoto.mobile.ui.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.iphoto.mobile.model.AlbumDTO
import org.iphoto.mobile.network.AlbumApi

data class AlbumsUiState(
    val albums: List<AlbumDTO> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

class AlbumsViewModel(private val albumApi: AlbumApi) : ViewModel() {
    private val _uiState = MutableStateFlow(AlbumsUiState())
    val uiState: StateFlow<AlbumsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.value = AlbumsUiState(isLoading = true)
        viewModelScope.launch {
            _uiState.value = try {
                val sortedAlbums = albumApi.list().sortedByDescending { it.createdAt }
                AlbumsUiState(albums = sortedAlbums, isLoading = false)
            } catch (e: Exception) {
                AlbumsUiState(isLoading = false, error = e.message ?: "Failed to load albums")
            }
        }
    }
}

class AlbumsViewModelFactory(private val albumApi: AlbumApi) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AlbumsViewModel(albumApi) as T
}
