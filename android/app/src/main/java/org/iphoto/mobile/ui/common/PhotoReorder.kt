package org.iphoto.mobile.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import kotlinx.coroutines.CancellationException
import org.iphoto.mobile.model.Photo

enum class DropPosition {
    BEFORE,
    AFTER
}

data class ReorderUpdate(
    val photoId: String,
    val newDate: String
)

data class ReorderDragState(
    val draggedPhoto: Photo,
    val movedPhotos: List<Photo>,
    val dragDay: String,
    val dragPosition: Offset,
    val targetId: String? = null,
    val dropPosition: DropPosition = DropPosition.BEFORE
)

/**
 * Pure date-math and gesture detection for drag-to-reorder-within-a-day, matching web-ui's photoReorder.js.
 *
 * The grid displays a day newest-first (dateTaken / fullDateTime DESC). Dropping photo(s)
 * between two neighbors P (displayed before the moved items, i.e. newer) and Q (displayed
 * after the moved items, i.e. older) needs the moved item(s) to land strictly between P and Q chronologically.
 *
 * Anchor: the moved item's new dateTaken is anchored off Q (the older, "after" neighbor) plus 1 second,
 * cascading outward for multi-item drag (falling back to a millisecond cascade in tight gaps).
 */
object PhotoReorder {
    private const val SECOND_MS = 1000L

    private val OUTPUT_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")

    private val INPUT_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder()
        .append(DateTimeFormatter.ISO_LOCAL_DATE)
        .appendLiteral('T')
        .append(DateTimeFormatter.ISO_LOCAL_TIME)
        .toFormatter()

    fun parseTime(fullDateTime: String): Long {
        val dt = LocalDateTime.parse(fullDateTime, INPUT_FORMATTER)
        return dt.toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    fun formatLocal(timeMs: Long): String {
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(timeMs), ZoneOffset.UTC)
        return dt.format(OUTPUT_FORMATTER)
    }

    private fun startOfDay(timeMs: Long): Long {
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(timeMs), ZoneOffset.UTC)
        val start = dt.toLocalDate().atStartOfDay()
        return start.toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    private fun endOfDay(timeMs: Long): Long {
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(timeMs), ZoneOffset.UTC)
        val end = dt.toLocalDate().atTime(23, 59, 59, 999_000_000)
        return end.toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    fun clampToDay(timeMs: Long, anchorTimeMs: Long): Long {
        val start = startOfDay(anchorTimeMs)
        val end = endOfDay(anchorTimeMs)
        return timeMs.coerceIn(start, end)
    }

    /**
     * @param dayPhotosDesc The day's non-moved photos in descending display order (newest first).
     * @param movedIds IDs being moved, in their original relative display order.
     * @param targetId Target photo ID in [dayPhotosDesc].
     * @param position Whether to drop before or after the target photo.
     * @return List of [ReorderUpdate], or empty list if target not found or no neighbor to anchor off.
     */
    fun computeReorderDates(
        dayPhotosDesc: List<Photo>,
        movedIds: List<String>,
        targetId: String,
        position: DropPosition
    ): List<ReorderUpdate> {
        val targetIndex = dayPhotosDesc.indexOfFirst { it.id == targetId }
        if (targetIndex == -1) return emptyList()

        val before = if (position == DropPosition.BEFORE) {
            if (targetIndex > 0) dayPhotosDesc[targetIndex - 1] else null
        } else {
            dayPhotosDesc[targetIndex]
        }

        val after = if (position == DropPosition.BEFORE) {
            dayPhotosDesc[targetIndex]
        } else {
            if (targetIndex + 1 < dayPhotosDesc.size) dayPhotosDesc[targetIndex + 1] else null
        }

        val n = movedIds.size
        if (n == 0) return emptyList()
        val times = LongArray(n)

        if (after != null) {
            // Anchor = after (Q): cascade outward from it, XN (nearest Q) = Q + 1s, ..., X1 = Q + N * 1s
            val anchor = parseTime(after.fullDateTime)
            for (k in 1..n) {
                times[n - k] = anchor + k * SECOND_MS
            }
            // Tight gap: seconds-cascade would collide with/exceed the "before" neighbor -> millisecond cascade
            if (before != null && times[0] >= parseTime(before.fullDateTime)) {
                for (k in 1..n) {
                    times[n - k] = anchor + k.toLong()
                }
            }
        } else if (before != null) {
            // No "after" neighbor (dropped at the day's oldest/last position) -> symmetric subtraction off "before" (P)
            val anchor = parseTime(before.fullDateTime)
            for (k in 1..n) {
                times[k - 1] = anchor - k * SECOND_MS
            }
        } else {
            return emptyList()
        }

        val anchorTimeMs = if (after != null) parseTime(after.fullDateTime) else parseTime(before!!.fullDateTime)
        return movedIds.mapIndexed { index, photoId ->
            val clamped = clampToDay(times[index], anchorTimeMs)
            ReorderUpdate(photoId = photoId, newDate = formatLocal(clamped))
        }
    }

    /**
     * Hit-tests [gridPos] against the visible photos belonging to a day to find the drop target.
     * Rejects drops outside the day's bounds (returns null).
     */
    fun findDropTarget(
        gridPos: Offset,
        dayPhotos: List<Photo>,
        movedIds: Set<String>,
        visibleItems: List<LazyGridItemInfo>
    ): Pair<String, DropPosition>? {
        val others = dayPhotos.filter { it.id !in movedIds }
        if (others.isEmpty()) return null

        val otherIds = others.map { it.id }.toSet()
        val dayIds = dayPhotos.map { it.id }.toSet()
        val visibleDayItems = visibleItems.filter { it.key in dayIds }
        if (visibleDayItems.isEmpty()) return null

        val dayTop = visibleDayItems.minOf { it.offset.y } - 50
        val dayBottom = visibleDayItems.maxOf { it.offset.y + it.size.height } + 50
        if (gridPos.y < dayTop || gridPos.y > dayBottom) {
            return null
        }

        val visibleOthers = visibleItems.filter { it.key in otherIds }
        if (visibleOthers.isEmpty()) {
            val isNearTop = gridPos.y <= dayTop + 80
            val target = if (isNearTop) others.first().id else others.last().id
            val pos = if (isNearTop) DropPosition.BEFORE else DropPosition.AFTER
            return target to pos
        }

        val firstOther = visibleOthers.first()
        val lastOther = visibleOthers.last()
        if (gridPos.y < firstOther.offset.y && others.first().id == firstOther.key) {
            return (firstOther.key as String) to DropPosition.BEFORE
        }
        if (gridPos.y > lastOther.offset.y + lastOther.size.height && others.last().id == lastOther.key) {
            return (lastOther.key as String) to DropPosition.AFTER
        }

        val closest = visibleOthers.minByOrNull { item ->
            val cx = item.offset.x + item.size.width / 2f
            val cy = item.offset.y + item.size.height / 2f
            val dx = gridPos.x - cx
            val dy = gridPos.y - cy
            dx * dx + dy * dy
        } ?: return null

        val cx = closest.offset.x + closest.size.width / 2f
        val dropPos = if (gridPos.x < cx) DropPosition.BEFORE else DropPosition.AFTER
        return (closest.key as String) to dropPos
    }
}

/**
 * Detects a 2-second finger hold to initiate moving an asset without interfering with selection:
 * - Releases under [longPressTimeoutMs] (~400ms) trigger [onTap].
 * - For an unselected photo: reaching [longPressTimeoutMs] triggers [onLongPress] to select it immediately.
 * - For an already-selected photo: holding > [longPressTimeoutMs] and releasing before [holdDurationMs]
 *   triggers [onLongPress] to unselect it.
 * - Maintaining the finger still for >= [holdDurationMs] (2000ms) triggers [onMoveStart], followed by
 *   [onMoveDrag] events until [onMoveEnd] on pointer release or [onMoveCancel].
 */
suspend fun PointerInputScope.detectHoldToMoveGestures(
    holdDurationMs: Long = 2000L,
    longPressTimeoutMs: Long = 400L,
    isSelected: () -> Boolean = { false },
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    onMoveStart: (Offset) -> Unit,
    onMoveDrag: (Offset) -> Unit,
    onMoveEnd: () -> Unit,
    onMoveCancel: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        val downPos = down.position
        val pointerId = down.id
        val wasAlreadySelected = isSelected()

        var isMoveActive = false

        // Phase 1: Wait up to longPressTimeoutMs (400ms) for tap or scroll
        val tapOrScroll = withTimeoutOrNull(longPressTimeoutMs) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == pointerId } ?: return@withTimeoutOrNull true
                if (change.isConsumed) {
                    // Child composable (e.g. selection button) consumed the event
                    return@withTimeoutOrNull true
                }
                if ((change.position - downPos).getDistance() > viewConfiguration.touchSlop) {
                    return@withTimeoutOrNull true // Scrolled before 400ms
                }
                if (change.changedToUp()) {
                    change.consume()
                    onTap()
                    return@withTimeoutOrNull true
                } else if (!change.pressed) {
                    return@withTimeoutOrNull true
                }
            }
        }

        if (tapOrScroll == true) {
            return@awaitEachGesture
        }

        // Phase 2: Wait remaining time until holdDurationMs (1600ms)
        // If released between 400ms and 2000ms, that's a regular long-press.
        val releasedBeforeMove = withTimeoutOrNull(holdDurationMs - longPressTimeoutMs) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == pointerId } ?: return@withTimeoutOrNull true
                if (change.isConsumed) {
                    // Child composable (e.g. selection button) consumed the event
                    return@withTimeoutOrNull true
                }
                if ((change.position - downPos).getDistance() > viewConfiguration.touchSlop) {
                    return@withTimeoutOrNull true // Scrolled before 2s
                }
                if (change.changedToUp()) {
                    change.consume()
                    onLongPress?.invoke()
                    return@withTimeoutOrNull true
                } else if (!change.pressed) {
                    return@withTimeoutOrNull true
                }
            }
        }

        if (releasedBeforeMove == true) {
            return@awaitEachGesture
        }

        // 2000ms reached! Start move mode directly without selecting the photo.
        isMoveActive = true
        onMoveStart(downPos)

        // Phase 3: Track drag movements
        try {
            while (isMoveActive) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == pointerId }
                if (change == null) {
                    isMoveActive = false
                    onMoveCancel()
                    break
                }
                val isUp = !change.pressed || change.changedToUp()
                change.consume()
                if (isUp) {
                    isMoveActive = false
                    onMoveEnd()
                    break
                } else {
                    onMoveDrag(change.position)
                }
            }
        } catch (e: CancellationException) {
            if (isMoveActive) {
                isMoveActive = false
                onMoveCancel()
            }
            throw e
        } finally {
            if (isMoveActive) {
                isMoveActive = false
                onMoveCancel()
            }
        }
    }
}
