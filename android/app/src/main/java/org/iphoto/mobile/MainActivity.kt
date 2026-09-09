package org.iphoto.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import org.iphoto.mobile.auth.AuthUiState
import org.iphoto.mobile.auth.AuthViewModel
import org.iphoto.mobile.auth.AuthViewModelFactory
import org.iphoto.mobile.ui.login.LoginScreen
import org.iphoto.mobile.ui.nav.MainScreen
import org.iphoto.mobile.ui.theme.IPhotoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as IPhotoApplication).container

        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                IPhotoTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val viewModel: AuthViewModel =
                            viewModel(factory = AuthViewModelFactory(container.authRepository))
                        IPhotoApp(viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun IPhotoApp(viewModel: AuthViewModel) {
    val state by viewModel.uiState.collectAsState()

    when (val current = state) {
        is AuthUiState.CheckingSession -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is AuthUiState.LoggedOut -> {
            LoginScreen(
                initialServerUrl = viewModel.initialServerUrl,
                isLoading = current.isLoading,
                errorMessage = current.error,
                onLogin = viewModel::login
            )
        }
        is AuthUiState.LoggedIn -> {
            MainScreen(onLogout = viewModel::logout)
        }
    }
}
