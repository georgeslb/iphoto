package org.iphoto.mobile.ui.spaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.iphoto.mobile.model.SpaceDTO
import org.iphoto.mobile.network.SpaceApi

data class SpacesUiState(
    val spaces: List<SpaceDTO> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

class SpacesViewModel(private val spaceApi: SpaceApi) : ViewModel() {
    private val _uiState = MutableStateFlow(SpacesUiState())
    val uiState: StateFlow<SpacesUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.value = SpacesUiState(isLoading = true)
        viewModelScope.launch {
            _uiState.value = try {
                SpacesUiState(spaces = spaceApi.list(), isLoading = false)
            } catch (e: Exception) {
                SpacesUiState(isLoading = false, error = e.message ?: "Failed to load spaces")
            }
        }
    }
}

class SpacesViewModelFactory(private val spaceApi: SpaceApi) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = SpacesViewModel(spaceApi) as T
}
