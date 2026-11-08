package org.iphoto.mobile.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import kotlinx.coroutines.launch

private val OUTPUT_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yy")

/**
 * Formats a day string for timeline display strictly as compact date "dd/MM/yy" (e.g. "30/11/25").
 * Parses ISO date/datetime (yyyy-MM-dd), localized month strings, or returns original if unparseable.
 */
fun formatTimelineDate(rawDate: String): String {
    val trimmed = rawDate.trim()
    if (trimmed.isEmpty()) return ""

    // 1. If already in dd/MM/yy format, return directly
    if (trimmed.matches(Regex("""\d{2}/\d{2}/\d{2}"""))) {
        return trimmed
    }

    // 2. ISO date or datetime (e.g. "2025-11-30" or "2025-11-30T14:30:00")
    try {
        val isoStr = if (trimmed.length >= 10 && trimmed[4] == '-' && trimmed[7] == '-') {
            trimmed.substring(0, 10)
        } else trimmed
        val parsed = LocalDate.parse(isoStr)
        return parsed.format(OUTPUT_DATE_FORMATTER)
    } catch (_: Exception) {}

    // 3. Common standard date patterns (strip day-of-week if present, e.g. "Monday, Jan 01, 2026" -> "Jan 01, 2026")
    val candidate = if (trimmed.contains(',')) {
        val afterComma = trimmed.substringAfter(',').trim()
        if (afterComma.any { it.isDigit() }) afterComma else trimmed
    } else trimmed

    val patterns = listOf(
        "dd/MM/yyyy",
        "MMM dd, yyyy",
        "MMM d, yyyy",
        "MMMM dd, yyyy",
        "MMMM d, yyyy",
        "d MMMM yyyy",
        "d MMM yyyy"
    )
    val locales = listOf(Locale.ENGLISH, Locale.US, Locale.getDefault())
    for (loc in locales) {
        for (pattern in patterns) {
            try {
                val parsed = LocalDate.parse(candidate, DateTimeFormatter.ofPattern(pattern, loc))
                return parsed.format(OUTPUT_DATE_FORMATTER)
            } catch (_: Exception) {}
        }
    }

    return trimmed
}

/**
 * Fast-scroll timeline scrollbar with a floating date indicator badge for photo grids.
 *
 * Appears clearly on the right edge of the grid.
 * - Always clearly visible with vertical track and high-contrast thumb handle.
 * - While scrubbing: smoothly moves the thumb and displays a floating date pill (dd/MM/yy) without scrolling the grid.
 * - On release ("au lâche de la main"): scrolls the grid to the selected day.
 *
 * @param gridState The [LazyGridState] of the grid to scroll.
 * @param totalItemsCount Total count of items currently in the grid.
 * @param getDateForIndex Resolves the date string for a given grid item index.
 * @param getIndexForScroll Maps the target item index to a target scroll index (e.g. the day's header index).
 * @param enabled Whether fast-scroll dragging is enabled.
 */
@Composable
fun TimelineFastScrollbar(
    gridState: LazyGridState,
    totalItemsCount: Int,
    getDateForIndex: (Int) -> String?,
    modifier: Modifier = Modifier,
    getIndexForScroll: ((Int) -> Int)? = null,
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

    val thumbHeightDp = 52.dp
    val thumbHeightPx = with(density) { thumbHeightDp.toPx() }

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
        // Floating date indicator pill next to user's finger (showing compact date e.g. "30/11/25")
        AnimatedVisibility(
            visible = isDragging && !currentDate.isNullOrBlank(),
            enter = fadeIn(tween(150)) + scaleIn(tween(150), initialScale = 0.85f),
            exit = fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset {
                    val halfBadge = if (badgeHeightPx > 0) badgeHeightPx / 2 else with(density) { 16.dp.toPx() }
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
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarToday,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = formatTimelineDate(currentDate.orEmpty()),
                        style = MaterialTheme.typography.labelMedium,
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
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                isDragging = true
                                var currentY = down.position.y
                                val fraction = ((currentY - thumbHeightPx / 2) / availableHeightPx).coerceIn(0f, 1f)
                                dragFraction = fraction

                                val pointerId = down.id
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == pointerId }
                                    if (change == null || change.isConsumed || !change.pressed) {
                                        break
                                    }
                                    currentY = change.position.y
                                    val newFraction = ((currentY - thumbHeightPx / 2) / availableHeightPx).coerceIn(0f, 1f)
                                    dragFraction = newFraction
                                    change.consume()
                                }

                                // Scroll to destination day only on release ("au lâche de la main")
                                isDragging = false
                                val target = (dragFraction * (totalItemsCount - 1)).roundToInt().coerceIn(0, totalItemsCount - 1)
                                val scrollIndex = getIndexForScroll?.invoke(target) ?: target
                                coroutineScope.launch { gridState.scrollToItem(scrollIndex) }
                            }
                        }
                    } else Modifier
                )
        ) {
            // Clearly visible vertical track along the right edge
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .fillMaxHeight()
                    .width(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
            )

            // Scrollbar thumb indicator (clearly visible at all times)
            val thumbWidth = if (isDragging) 8.dp else 6.dp
            val thumbShape = RoundedCornerShape(thumbWidth / 2)
            val thumbColor = if (isDragging) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(x = -with(density) { 3.dp.roundToPx() }, y = thumbOffsetY.roundToInt()) }
                    .size(width = thumbWidth, height = thumbHeightDp)
                    .shadow(elevation = if (isDragging) 4.dp else 1.dp, shape = thumbShape)
                    .clip(thumbShape)
                    .background(thumbColor)
            )
        }
    }
}
