package org.iphoto.mobile.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.iphoto.mobile.model.Photo

data class PagedPhotosUiState(
    val photos: List<Photo> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val endReached: Boolean = false
)

/**
 * Generic pager shared by Library/Album-detail/Space-detail - GET /api/photos, GET
 * /api/albums/{id}/photos and GET /api/spaces/{id}/photos all page a list of Photo, just with two
 * different JSON envelopes (see PhotoPageResponse vs SpringPage). [loadPage] adapts whichever
 * envelope the caller's endpoint uses down to (items, isLastPage).
 */
class PagedPhotoGridViewModel(
    private val loadPage: suspend (page: Int, size: Int) -> Pair<List<Photo>, Boolean>
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
                    endReached = last
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingMore = false, error = e.message)
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 60
    }
}

class PagedPhotoGridViewModelFactory(
    private val loadPage: suspend (page: Int, size: Int) -> Pair<List<Photo>, Boolean>
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        PagedPhotoGridViewModel(loadPage) as T
}
