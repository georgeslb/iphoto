package org.iphoto.mobile.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
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
    val hapticFeedback = LocalHapticFeedback.current
    var showAlbumPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    var gridCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val gridState = rememberLazyGridState()
    var dragState by remember { mutableStateOf<ReorderDragState?>(null) }
    var reorderSelectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var reorderDay by remember { mutableStateOf<String?>(null) }

    // Auto-scroll when dragging near viewport edges
    LaunchedEffect(dragState != null) {
        if (dragState == null) return@LaunchedEffect
        while (true) {
            val pos = dragState?.dragPosition
            val coords = gridCoordinates
            if (pos != null && coords != null && coords.isAttached) {
                val height = coords.size.height.toFloat()
                if (height > 0) {
                    val edge = 150f
                    if (pos.y < edge) {
                        val factor = ((edge - pos.y) / edge).coerceIn(0f, 1f)
                        gridState.scrollBy(-25f * factor)
                    } else if (pos.y > height - edge) {
                        val factor = ((pos.y - (height - edge)) / edge).coerceIn(0f, 1f)
                        gridState.scrollBy(25f * factor)
                    }
                }
            }
            delay(16)
        }
    }

    // Keeps PhotoBrowsingContext in sync with what's actually on screen, so the lightbox's
    // next/previous arrows always reflect this grid's current (possibly paginated) order.
    LaunchedEffect(state.photos) { PhotoBrowsingContext.updatePhotos(state.photos) }

    BackHandler(enabled = reorderSelectedIds.isNotEmpty() || state.isSelectionMode) {
        if (reorderSelectedIds.isNotEmpty()) {
            reorderSelectedIds = emptySet()
            reorderDay = null
        } else {
            viewModel.clearSelection()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .onGloballyPositioned { gridCoordinates = it }
        ) {
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
                    val movedIds = remember(dragState) { dragState?.movedPhotos?.map { it.id }?.toSet() ?: emptySet() }

                    LazyVerticalGrid(
                        state = gridState,
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
                                val isReorderSelected = photo.id in reorderSelectedIds
                                val isMoved = photo.id in movedIds
                                val showReorderButton = !state.isSelectionMode && (reorderDay == null || photo.groupDate == reorderDay)

                                Box(modifier = Modifier.aspectRatio(1f)) {
                                    PhotoThumbnail(
                                        imageUrl = container.absoluteUrl(photo.url),
                                        isVideo = photo.contentType?.startsWith("video") == true,
                                        duration = photo.duration,
                                        isSelectionMode = state.isSelectionMode,
                                        isSelected = isSelected,
                                        isReorderSelected = isReorderSelected,
                                        showReorderButton = showReorderButton,
                                        onLongClick = {
                                            if (reorderSelectedIds.isNotEmpty()) {
                                                reorderSelectedIds = emptySet()
                                                reorderDay = null
                                            }
                                            viewModel.toggleSelection(photo.id)
                                        },
                                        onSelectClick = {
                                            reorderSelectedIds = emptySet()
                                            reorderDay = null
                                            viewModel.toggleSelection(photo.id)
                                        },
                                        onReorderClick = {
                                            if (reorderDay == null || photo.groupDate != reorderDay) {
                                                reorderDay = photo.groupDate
                                                reorderSelectedIds = setOf(photo.id)
                                            } else {
                                                val next = if (photo.id in reorderSelectedIds) {
                                                    reorderSelectedIds - photo.id
                                                } else {
                                                    reorderSelectedIds + photo.id
                                                }
                                                reorderSelectedIds = next
                                                if (next.isEmpty()) {
                                                    reorderDay = null
                                                }
                                            }
                                        },
                                        onHoldToMoveStart = if (!state.isMutating) {
                                            { windowPos ->
                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                val gridPos = gridCoordinates?.takeIf { it.isAttached }?.windowToLocal(windowPos) ?: windowPos
                                                val dayList = dayGroups[photo.groupDate] ?: emptyList()
                                                val currentGroup = if (reorderDay == photo.groupDate) reorderSelectedIds else emptySet()
                                                val updatedGroup = if (photo.id in currentGroup) currentGroup else currentGroup + photo.id
                                                reorderDay = photo.groupDate
                                                reorderSelectedIds = updatedGroup
                                                val movingList = dayList.filter { it.id in updatedGroup }
                                                val target = PhotoReorder.findDropTarget(
                                                    gridPos = gridPos,
                                                    dayPhotos = dayList,
                                                    movedIds = movingList.map { it.id }.toSet(),
                                                    visibleItems = gridState.layoutInfo.visibleItemsInfo
                                                )
                                                dragState = ReorderDragState(
                                                    draggedPhoto = photo,
                                                    movedPhotos = if (movingList.isNotEmpty()) movingList else listOf(photo),
                                                    dragDay = photo.groupDate,
                                                    dragPosition = gridPos,
                                                    targetId = target?.first,
                                                    dropPosition = target?.second ?: DropPosition.BEFORE
                                                )
                                            }
                                        } else null,
                                        onHoldToMoveDrag = if (!state.isMutating) {
                                            { windowPos ->
                                                val current = dragState
                                                if (current != null) {
                                                    val gridPos = gridCoordinates?.takeIf { it.isAttached }?.windowToLocal(windowPos) ?: windowPos
                                                    val dayList = dayGroups[current.dragDay] ?: emptyList()
                                                    val target = PhotoReorder.findDropTarget(
                                                        gridPos = gridPos,
                                                        dayPhotos = dayList,
                                                        movedIds = current.movedPhotos.map { it.id }.toSet(),
                                                        visibleItems = gridState.layoutInfo.visibleItemsInfo
                                                    )
                                                    dragState = current.copy(
                                                        dragPosition = gridPos,
                                                        targetId = target?.first,
                                                        dropPosition = target?.second ?: current.dropPosition
                                                    )
                                                }
                                            }
                                        } else null,
                                        onHoldToMoveEnd = {
                                            val current = dragState
                                            dragState = null
                                            if (current != null && current.targetId != null && current.targetId !in current.movedPhotos.map { it.id }) {
                                                val dayList = dayGroups[current.dragDay] ?: emptyList()
                                                val moving = current.movedPhotos.map { it.id }
                                                val others = dayList.filter { it.id !in moving.toSet() }
                                                val updates = PhotoReorder.computeReorderDates(
                                                    dayPhotosDesc = others,
                                                    movedIds = moving,
                                                    targetId = current.targetId,
                                                    position = current.dropPosition
                                                )
                                                if (updates.isNotEmpty()) {
                                                    reorderSelectedIds = emptySet()
                                                    reorderDay = null
                                                    viewModel.reorderPhotos(container.photoApi, updates)
                                                }
                                            }
                                        },
                                        onHoldToMoveCancel = {
                                            dragState = null
                                        },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .alpha(if (isMoved) 0.35f else 1f),
                                        onClick = {
                                            if (reorderSelectedIds.isNotEmpty()) {
                                                reorderSelectedIds = emptySet()
                                                reorderDay = null
                                            }
                                            if (state.isSelectionMode) {
                                                viewModel.toggleSelection(photo.id)
                                            } else {
                                                onPhotoClick(photo.id)
                                            }
                                        }
                                    )

                                    if (dragState?.targetId == photo.id) {
                                        val isBefore = dragState?.dropPosition == DropPosition.BEFORE
                                        Box(
                                            modifier = Modifier
                                                .align(if (isBefore) Alignment.CenterStart else Alignment.CenterEnd)
                                                .fillMaxHeight()
                                                .width(4.dp)
                                                .background(
                                                    color = Color(0xFF4CAF50),
                                                    shape = RoundedCornerShape(2.dp)
                                                )
                                        )
                                    }
                                }
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

            // Floating drag preview under finger
            dragState?.let { current ->
                val density = LocalDensity.current
                val xDp = with(density) { current.dragPosition.x.toDp() }
                val yDp = with(density) { current.dragPosition.y.toDp() }
                val cardSize = 80.dp

                Box(
                    modifier = Modifier
                        .offset(x = xDp - cardSize / 2, y = yDp - cardSize / 2)
                        .size(cardSize)
                        .shadow(12.dp, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(3.dp, Color(0xFF4CAF50), RoundedCornerShape(8.dp))
                ) {
                    AsyncImage(
                        model = container.absoluteUrl(current.draggedPhoto.url),
                        imageLoader = container.imageLoader,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (current.movedPhotos.size > 1) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .background(Color(0xFF4CAF50), CircleShape)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${current.movedPhotos.size}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                            )
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
                    { viewModel.removeSelectionFromAlbum(container.albumApi, container::notifyAlbumsChanged) }
                } else null,
                onMoveToMain = if (viewModel.canMoveToMain) {
                    { viewModel.moveSelectionToMain(container.photoApi) }
                } else null
            )
        }
    }

    if (showAlbumPicker) {
        AlbumPickerDialog(
            albumApi = container.albumApi,
            onDismiss = { showAlbumPicker = false },
            onAlbumPicked = { albumId ->
                viewModel.addSelectionToAlbum(container.albumApi, albumId, container::notifyAlbumsChanged)
                showAlbumPicker = false
            },
            onCreateAlbum = { name ->
                viewModel.createAlbumAndAddSelection(container.albumApi, name, container::notifyAlbumsChanged)
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
