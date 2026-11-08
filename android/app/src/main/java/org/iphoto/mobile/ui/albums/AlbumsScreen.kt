package org.iphoto.mobile.ui.albums

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import org.iphoto.mobile.LocalAppContainer
import org.iphoto.mobile.model.AlbumDTO
import org.iphoto.mobile.ui.common.PhotoThumbnail

@Composable
fun AlbumsScreen(onAlbumClick: (String) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: AlbumsViewModel = viewModel(factory = AlbumsViewModelFactory(container.albumApi))
    val state by viewModel.uiState.collectAsState()
    val albumsChangeVersion by container.albumsChangeVersion.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    var skippedInitialResume by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (skippedInitialResume) {
                    viewModel.load()
                } else {
                    skippedInitialResume = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(albumsChangeVersion) {
        if (albumsChangeVersion > 0) {
            viewModel.load()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if ((!state.isLoading && state.error == null && state.albums.isNotEmpty()) || state.searchQuery.isNotEmpty()) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Filter albums...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search"
                    )
                },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear filter"
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when {
                state.isLoading && state.albums.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                state.error != null && state.albums.isEmpty() -> {
                    Text(
                        state.error ?: "",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    )
                }
                state.albums.isEmpty() -> {
                    Text("No albums yet", modifier = Modifier.align(Alignment.Center))
                }
                state.filteredAlbums.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "No albums matching \"${state.searchQuery}\"",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { viewModel.onSearchQueryChange("") }) {
                            Text("Clear filter")
                        }
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.filteredAlbums, key = { it.id }) { album ->
                            AlbumRow(album = album, onClick = { onAlbumClick(album.id) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumRow(album: AlbumDTO, onClick: () -> Unit) {
    val container = LocalAppContainer.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val coverUrl = album.coverPhoto?.url
        if (coverUrl != null) {
            PhotoThumbnail(
                imageUrl = container.absoluteUrl(coverUrl),
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                onClick = onClick
            )
        } else {
            Box(modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))
        }
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(album.name, style = MaterialTheme.typography.titleMedium)
            Text("${album.photoCount ?: 0} photos", style = MaterialTheme.typography.bodySmall)
        }
    }
}
