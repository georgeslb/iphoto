package org.iphoto.mobile.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Formats a day string for timeline display.
 * If the input is in ISO format (yyyy-MM-dd), converts it to a localized display date (e.g. "15 mars 2024").
 * Otherwise, returns the input string directly.
 */
fun formatTimelineDate(rawDate: String): String {
    return try {
        val parsed = LocalDate.parse(rawDate.trim())
        val formatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())
        parsed.format(formatter)
    } catch (_: Exception) {
        rawDate
    }
}

/**
 * Fast-scroll timeline scrollbar with a floating date indicator badge for photo grids.
 *
 * Appears on the right edge of the grid. When dragged:
 * 1. Rapidly scrolls the [gridState] to the proportional item index.
 * 2. Displays a floating pill/badge next to the user's finger showing the date corresponding to that position.
 * 3. Fades out smoothly when idle.
 *
 * @param gridState The [LazyGridState] of the grid to scroll.
 * @param totalItemsCount Total count of items currently in the grid.
 * @param getDateForIndex Function that resolves the day string for a given grid item index.
 * @param enabled Whether fast-scroll dragging is enabled (disable during drag-and-drop reordering).
 */
@Composable
fun TimelineFastScrollbar(
    gridState: LazyGridState,
    totalItemsCount: Int,
    getDateForIndex: (Int) -> String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    if (totalItemsCount <= 0) return

    val visibleCount = gridState.layoutInfo.visibleItemsInfo.size
    // Do not show scrollbar if all items fit on screen without scrolling
    if (visibleCount >= totalItemsCount && totalItemsCount > 0) return

    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var containerHeightPx by remember { mutableFloatStateOf(0f) }
    var badgeHeightPx by remember { mutableFloatStateOf(0f) }

    val thumbHeightDp = 48.dp
    val thumbHeightPx = with(density) { thumbHeightDp.toPx() }

    // Fade-out timer: keeps thumb visible while scrolling and for 1.5s afterward
    var showThumbUntil by remember { mutableLongStateOf(0L) }
    var isRecentScroll by remember { mutableStateOf(false) }

    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress) {
            showThumbUntil = System.currentTimeMillis() + 1500L
            isRecentScroll = true
        }
    }

    LaunchedEffect(gridState.isScrollInProgress, showThumbUntil) {
        if (!gridState.isScrollInProgress) {
            val remaining = showThumbUntil - System.currentTimeMillis()
            if (remaining > 0) {
                isRecentScroll = true
                delay(remaining)
            }
            isRecentScroll = false
        }
    }

    val shouldShow = isDragging || isRecentScroll
    val scrollbarAlpha by animateFloatAsState(
        targetValue = if (shouldShow) 1f else 0f,
        animationSpec = tween(durationMillis = if (shouldShow) 150 else 700),
        label = "timelineScrollbarAlpha"
    )

    // Compute scroll progress from first visible item
    val firstVisible = gridState.firstVisibleItemIndex
    val maxFirstIndex = (totalItemsCount - visibleCount).coerceAtLeast(1)
    val computedScrollFraction = (firstVisible.toFloat() / maxFirstIndex).coerceIn(0f, 1f)

    val currentFraction = if (isDragging) dragFraction else computedScrollFraction
    val availableHeightPx = (containerHeightPx - thumbHeightPx).coerceAtLeast(0f)
    val thumbOffsetY = currentFraction * availableHeightPx

    // Resolve date corresponding to target item index
    val activeIndex = (currentFraction * (totalItemsCount - 1)).roundToInt().coerceIn(0, totalItemsCount - 1)
    val currentDate = remember(activeIndex) { getDateForIndex(activeIndex) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(50.dp)
            .onGloballyPositioned { coordinates ->
                containerHeightPx = coordinates.size.height.toFloat()
            }
    ) {
        // Floating date indicator pill next to user's finger
        AnimatedVisibility(
            visible = isDragging && !currentDate.isNullOrBlank(),
            enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.85f),
            exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset {
                    val halfBadge = if (badgeHeightPx > 0) badgeHeightPx / 2 else with(density) { 18.dp.toPx() }
                    val targetY = (thumbOffsetY + thumbHeightPx / 2 - halfBadge)
                        .coerceIn(8.dp.toPx(), (containerHeightPx - halfBadge * 2 - 8.dp.toPx()).coerceAtLeast(8.dp.toPx()))
                    IntOffset(
                        x = -with(density) { 46.dp.roundToPx() },
                        y = targetY.roundToInt()
                    )
                }
                .onGloballyPositioned { coordinates ->
                    badgeHeightPx = coordinates.size.height.toFloat()
                }
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarToday,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = formatTimelineDate(currentDate.orEmpty()),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Scroll track touch area and thumb handle
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(44.dp)
                .align(Alignment.CenterEnd)
                .then(
                    if (enabled && availableHeightPx > 0f) {
                        Modifier.pointerInput(totalItemsCount, availableHeightPx) {
                            detectVerticalDragGestures(
                                onDragStart = { offset ->
                                    isDragging = true
                                    val fraction = ((offset.y - thumbHeightPx / 2) / availableHeightPx).coerceIn(0f, 1f)
                                    dragFraction = fraction
                                    val target = (fraction * (totalItemsCount - 1)).roundToInt().coerceIn(0, totalItemsCount - 1)
                                    coroutineScope.launch { gridState.scrollToItem(target) }
                                },
                                onVerticalDrag = { change, _ ->
                                    change.consume()
                                    val fraction = ((change.position.y - thumbHeightPx / 2) / availableHeightPx).coerceIn(0f, 1f)
                                    dragFraction = fraction
                                    val target = (fraction * (totalItemsCount - 1)).roundToInt().coerceIn(0, totalItemsCount - 1)
                                    coroutineScope.launch { gridState.scrollToItem(target) }
                                },
                                onDragEnd = { isDragging = false },
                                onDragCancel = { isDragging = false }
                            )
                        }
                    } else Modifier
                )
        ) {
            // Scrollbar thumb indicator
            val thumbWidth = if (isDragging) 8.dp else 4.dp
            val thumbShape = RoundedCornerShape(thumbWidth / 2)
            val thumbColor = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(x = -with(density) { 3.dp.roundToPx() }, y = thumbOffsetY.roundToInt()) }
                    .size(width = thumbWidth, height = thumbHeightDp)
                    .alpha(scrollbarAlpha)
                    .clip(thumbShape)
                    .background(thumbColor)
            )
        }
    }
}
