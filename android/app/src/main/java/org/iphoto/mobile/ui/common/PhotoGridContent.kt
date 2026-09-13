package org.iphoto.mobile.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.iphoto.mobile.LocalAppContainer

/**
 * Google/Android Photos-style day header: a full-width label above that day's row(s) of square
 * thumbnails - `groupDate` is already a display-ready day string from the backend (same field the
 * web app's day-indexed PhotoGrid.jsx groups by). Tapping it toggles selection for the whole day
 * (works even before selection mode has started, as a shortcut into it) - the "select all photos
 * for a day" affordance stock Photos apps offer via their own day-header tap.
 */
@Composable
private fun DayHeader(day: String, onClick: () -> Unit) {
    Text(
        text = day,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp)
    )
}

@Composable
fun PhotoGridContent(
    state: PagedPhotosUiState,
    viewModel: PagedPhotoGridViewModel,
    onPhotoClick: (String) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    var showAlbumPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    BackHandler(enabled = state.isSelectionMode) { viewModel.clearSelection() }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
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
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        dayGroups.forEach { (day, dayPhotos) ->
                            item(key = "header-$day", span = { GridItemSpan(maxLineSpan) }) {
                                DayHeader(day) { viewModel.toggleDaySelection(dayPhotos.map { it.id }) }
                            }
                            items(dayPhotos, key = { it.id }) { photo ->
                                val isSelected = photo.id in state.selectedIds
                                PhotoThumbnail(
                                    imageUrl = container.absoluteUrl(photo.url),
                                    isVideo = photo.contentType?.startsWith("video") == true,
                                    isSelectionMode = state.isSelectionMode,
                                    isSelected = isSelected,
                                    onLongClick = { viewModel.toggleSelection(photo.id) },
                                    modifier = Modifier.aspectRatio(1f),
                                    onClick = {
                                        if (state.isSelectionMode) {
                                            viewModel.toggleSelection(photo.id)
                                        } else {
                                            onPhotoClick(photo.id)
                                        }
                                    }
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

            if (state.actionError != null) {
                Text(
                    state.actionError,
                    color = MaterialTheme.colorScheme.onError,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.error)
                        .clickable { viewModel.dismissActionError() }
                        .padding(12.dp)
                )
            }
        }

        if (state.isSelectionMode) {
            SelectionActionBar(
                selectedCount = state.selectedIds.size,
                enabled = !state.isMutating,
                onCancel = viewModel::clearSelection,
                onMoveToGarbage = { viewModel.moveSelectionToGarbage(container.photoApi) },
                onAddToAlbum = { showAlbumPicker = true },
                onEditDate = { showDatePicker = true },
                onRemoveFromAlbum = if (viewModel.albumId != null) {
                    { viewModel.removeSelectionFromAlbum(container.albumApi) }
                } else null,
                onExcludeFromSync = if (viewModel.canExcludeFromSync) {
                    { viewModel.excludeSelectionFromSync(container.photoApi) }
                } else null
            )
        }
    }

    if (showAlbumPicker) {
        AlbumPickerDialog(
            albumApi = container.albumApi,
            onDismiss = { showAlbumPicker = false },
            onAlbumPicked = { albumId ->
                viewModel.addSelectionToAlbum(container.albumApi, albumId)
                showAlbumPicker = false
            },
            onCreateAlbum = { name ->
                viewModel.createAlbumAndAddSelection(container.albumApi, name)
                showAlbumPicker = false
            }
        )
    }

    if (showDatePicker) {
        val firstSelected = state.photos.firstOrNull { it.id in state.selectedIds }
        val initial = firstSelected?.fullDateTime?.let {
            try {
                LocalDateTime.parse(it, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            } catch (e: Exception) {
                LocalDateTime.now()
            }
        } ?: LocalDateTime.now()
        DateTimePickerDialog(
            initial = initial,
            onDismiss = { showDatePicker = false },
            onConfirm = { newDate ->
                viewModel.updateSelectionDate(container.photoApi, newDate)
                showDatePicker = false
            }
        )
    }
}
