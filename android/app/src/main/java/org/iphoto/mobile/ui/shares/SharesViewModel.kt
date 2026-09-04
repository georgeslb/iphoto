package org.iphoto.mobile.ui.shares

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.iphoto.mobile.model.ShareResponse
import org.iphoto.mobile.network.ShareApi

data class SharesUiState(
    val shares: List<ShareResponse> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

class SharesViewModel(private val shareApi: ShareApi) : ViewModel() {
    private val _uiState = MutableStateFlow(SharesUiState())
    val uiState: StateFlow<SharesUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.value = SharesUiState(isLoading = true)
        viewModelScope.launch {
            _uiState.value = try {
                SharesUiState(shares = shareApi.myShares(), isLoading = false)
            } catch (e: Exception) {
                SharesUiState(isLoading = false, error = e.message ?: "Failed to load shares")
            }
        }
    }
}

class SharesViewModelFactory(private val shareApi: ShareApi) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SharesViewModel(shareApi) as T
}
