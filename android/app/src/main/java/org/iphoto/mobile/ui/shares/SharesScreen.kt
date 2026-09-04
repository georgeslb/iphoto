package org.iphoto.mobile.ui.shares

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.iphoto.mobile.LocalAppContainer

@Composable
fun SharesScreen() {
    val container = LocalAppContainer.current
    val viewModel: SharesViewModel = viewModel(factory = SharesViewModelFactory(container.shareApi))
    val state by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            state.error != null -> Text(
                state.error ?: "",
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )
            state.shares.isEmpty() -> Text("No shares yet", modifier = Modifier.align(Alignment.Center))
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.shares, key = { it.id }) { share ->
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(share.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            buildString {
                                append(if (share.isPasswordProtected) "Password protected" else "Public link")
                                if (share.expired) append(" · Expired")
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
