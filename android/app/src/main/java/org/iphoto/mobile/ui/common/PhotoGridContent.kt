package org.iphoto.mobile.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.iphoto.mobile.LocalAppContainer

@Composable
fun PhotoGridContent(
    state: PagedPhotosUiState,
    onPhotoClick: (String) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            state.error != null && state.photos.isEmpty() -> Text(
                state.error,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
            )
            state.photos.isEmpty() -> Text("No photos", modifier = Modifier.align(Alignment.Center))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.photos, key = { it.id }) { photo ->
                    PhotoThumbnail(
                        imageUrl = container.absoluteUrl(photo.url),
                        modifier = Modifier.aspectRatio(1f),
                        onClick = { onPhotoClick(photo.id) }
                    )
                }
                if (!state.endReached) {
                    item {
                        LaunchedEffect(Unit) { onLoadMore() }
                        Box(modifier = Modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
    }
}
