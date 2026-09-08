package org.iphoto.mobile.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.item
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.iphoto.mobile.LocalAppContainer

/**
 * Google/Android Photos-style day header: a full-width label above that day's row(s) of square
 * thumbnails - `groupDate` is already a display-ready day string from the backend (same field the
 * web app's day-indexed PhotoGrid.jsx groups by).
 */
@Composable
private fun DayHeader(day: String) {
    Text(
        text = day,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 10.dp)
    )
}

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
            else -> {
                // Photos already arrive newest-first from the backend, and Kotlin's groupBy keeps
                // both key-encounter order and each group's original element order - so this stays
                // newest-first-by-day without needing to re-sort anything.
                val dayGroups = remember(state.photos) { state.photos.groupBy { it.groupDate } }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    dayGroups.forEach { (day, dayPhotos) ->
                        item(key = "header-$day", span = { GridItemSpan(maxLineSpan) }) {
                            DayHeader(day)
                        }
                        items(dayPhotos, key = { it.id }) { photo ->
                            PhotoThumbnail(
                                imageUrl = container.absoluteUrl(photo.url),
                                isVideo = photo.contentType?.startsWith("video") == true,
                                modifier = Modifier.aspectRatio(1f),
                                onClick = { onPhotoClick(photo.id) }
                            )
                        }
                    }
                    if (!state.endReached) {
                        item(key = "load-more", span = { GridItemSpan(maxLineSpan) }) {
                            LaunchedEffect(Unit) { onLoadMore() }
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }
}
