package org.iphoto.mobile.ui.albums

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.iphoto.mobile.LocalAppContainer
import org.iphoto.mobile.model.AlbumDTO
import org.iphoto.mobile.ui.common.PhotoThumbnail

@Composable
fun AlbumsScreen(onAlbumClick: (String) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: AlbumsViewModel = viewModel(factory = AlbumsViewModelFactory(container.albumApi))
    val state by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            state.error != null -> Text(
                state.error ?: "",
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )
            state.albums.isEmpty() -> Text("No albums yet", modifier = Modifier.align(Alignment.Center))
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.albums, key = { it.id }) { album ->
                    AlbumRow(album = album, onClick = { onAlbumClick(album.id) })
                    HorizontalDivider()
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
