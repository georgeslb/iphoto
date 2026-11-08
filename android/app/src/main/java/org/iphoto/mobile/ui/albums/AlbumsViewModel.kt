package org.iphoto.mobile.ui.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.iphoto.mobile.model.AlbumDTO
import org.iphoto.mobile.network.AlbumApi

data class AlbumsUiState(
    val albums: List<AlbumDTO> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val error: String? = null
) {
    val filteredAlbums: List<AlbumDTO>
        get() = if (searchQuery.isBlank()) {
            albums
        } else {
            val query = searchQuery.trim().lowercase()
            albums.filter { it.name.lowercase().contains(query) }
        }
}

class AlbumsViewModel(private val albumApi: AlbumApi) : ViewModel() {
    private val _uiState = MutableStateFlow(AlbumsUiState())
    val uiState: StateFlow<AlbumsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun load() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val sortedAlbums = albumApi.list().sortedByDescending { it.createdAt }
                _uiState.update { it.copy(albums = sortedAlbums, isLoading = false, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Failed to load albums") }
            }
        }
    }
}

class AlbumsViewModelFactory(private val albumApi: AlbumApi) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AlbumsViewModel(albumApi) as T
}
