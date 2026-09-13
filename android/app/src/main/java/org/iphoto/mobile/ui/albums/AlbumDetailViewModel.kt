package org.iphoto.mobile.ui.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.iphoto.mobile.model.ShareRequest
import org.iphoto.mobile.model.ShareResponse
import org.iphoto.mobile.network.AlbumApi
import org.iphoto.mobile.network.ShareApi

data class AlbumDetailUiState(
    val albumName: String? = null,
    val isCreatingShare: Boolean = false,
    val createdShare: ShareResponse? = null,
    val shareError: String? = null
)

/**
 * Holds the album-level concerns AlbumDetailScreen needs beyond the photo grid itself (which
 * PagedPhotoGridViewModel already owns): the album's display name for the top bar, and creating a
 * share link for it. Kept separate from PagedPhotoGridViewModel deliberately - that one is shared
 * generically across Library/Album/Space and has no business knowing about album metadata/sharing.
 */
class AlbumDetailViewModel(
    private val albumApi: AlbumApi,
    private val albumId: String
) : ViewModel() {
    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(albumName = albumApi.get(albumId).name)
            } catch (e: Exception) {
                // Title just stays blank ("Album" fallback in the UI) - not worth a hard error for
                // what's a nice-to-have label, the photo grid load has its own error handling.
            }
        }
    }

    fun createShare(shareApi: ShareApi, name: String, daysAvailable: Int, password: String?) {
        _uiState.value = _uiState.value.copy(isCreatingShare = true, shareError = null)
        viewModelScope.launch {
            _uiState.value = try {
                val share = shareApi.createShare(
                    ShareRequest(
                        name = name,
                        daysAvailable = daysAvailable,
                        password = password?.takeIf { it.isNotBlank() },
                        albumId = albumId
                    )
                )
                _uiState.value.copy(isCreatingShare = false, createdShare = share)
            } catch (e: Exception) {
                _uiState.value.copy(isCreatingShare = false, shareError = e.message ?: "Failed to create share link")
            }
        }
    }

    fun dismissShareResult() {
        _uiState.value = _uiState.value.copy(createdShare = null, shareError = null)
    }
}

class AlbumDetailViewModelFactory(
    private val albumApi: AlbumApi,
    private val albumId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AlbumDetailViewModel(albumApi, albumId) as T
}
