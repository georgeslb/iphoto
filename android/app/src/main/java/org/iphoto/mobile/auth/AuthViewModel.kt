package org.iphoto.mobile.auth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.iphoto.mobile.model.UserDto

private const val TAG = "AuthViewModel"

sealed interface AuthUiState {
    data object CheckingSession : AuthUiState
    data class LoggedOut(val isLoading: Boolean = false, val error: String? = null) : AuthUiState
    data class LoggedIn(val user: UserDto) : AuthUiState
}

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.CheckingSession)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    val initialServerUrl: String get() = repository.storedServerUrl ?: ""

    init {
        resumeSession()
    }

    private fun resumeSession() {
        if (!repository.isLoggedIn) {
            _uiState.value = AuthUiState.LoggedOut()
            return
        }
        viewModelScope.launch {
            _uiState.value = try {
                AuthUiState.LoggedIn(repository.currentUser())
            } catch (e: Exception) {
                // stored token missing/revoked/unreachable - fall back to login rather than a stuck spinner
                AuthUiState.LoggedOut()
            }
        }
    }

    fun login(serverUrl: String, username: String, password: String) {
        _uiState.value = AuthUiState.LoggedOut(isLoading = true)
        viewModelScope.launch {
            _uiState.value = try {
                AuthUiState.LoggedIn(repository.login(serverUrl, username, password))
            } catch (e: Exception) {
                Log.e(TAG, "login: failed for serverUrl=$serverUrl", e)
                AuthUiState.LoggedOut(error = e.message ?: "Login failed")
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            _uiState.value = AuthUiState.LoggedOut()
        }
    }
}

class AuthViewModelFactory(private val repository: AuthRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AuthViewModel(repository) as T
}
