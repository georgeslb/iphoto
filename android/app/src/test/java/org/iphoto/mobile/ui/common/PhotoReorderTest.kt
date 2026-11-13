package org.iphoto.mobile.ui.common

import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.iphoto.mobile.model.Photo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoReorderTest {

    private val day = "2024-03-15"

    private fun photo(id: String, time: String): Photo = Photo(
        id = id,
        title = id,
        url = "/test/$id.jpg",
        date = day,
        fullDateTime = "${day}T$time",
        yearMonth = "2024-03",
        groupDate = day,
        size = null,
        resolution = "100x100",
        location = null,
        camera = null,
        contentType = "image/jpeg",
        fileHash = null,
        width = 100,
        height = 100,
        fileSystemCreationDate = null,
        duplicates = null
    )

    @Test
    fun `anchors off the OLDER after neighbor, not the newer one`() {
        val p = photo("p", "14:00:10")
        val q = photo("q", "14:00:05")

        val result = PhotoReorder.computeReorderDates(listOf(p, q), listOf("x"), "q", DropPosition.BEFORE)

        assertEquals(listOf(ReorderUpdate("x", "${day}T14:00:06.000")), result)
    }

    @Test
    fun `dropping after a target uses that target as before neighbor and next as anchor`() {
        val p = photo("p", "14:00:10")
        val q = photo("q", "14:00:05")

        val result = PhotoReorder.computeReorderDates(listOf(p, q), listOf("x"), "p", DropPosition.AFTER)

        assertEquals(listOf(ReorderUpdate("x", "${day}T14:00:06.000")), result)
    }

    @Test
    fun `dropped at very start of day anchors off after-neighbor only`() {
        val q = photo("q", "14:00:05")

        val result = PhotoReorder.computeReorderDates(listOf(q), listOf("x"), "q", DropPosition.BEFORE)

        assertEquals(listOf(ReorderUpdate("x", "${day}T14:00:06.000")), result)
    }

    @Test
    fun `dropped at very end of day subtracts from before-neighbor instead`() {
        val p = photo("p", "14:00:10")

        val result = PhotoReorder.computeReorderDates(listOf(p), listOf("x"), "p", DropPosition.AFTER)

        assertEquals(listOf(ReorderUpdate("x", "${day}T14:00:09.000")), result)
    }

    @Test
    fun `multiple moved items cascade seconds outward from anchor, preserving original order`() {
        val p = photo("p", "14:00:20")
        val q = photo("q", "14:00:05")

        val result = PhotoReorder.computeReorderDates(listOf(p, q), listOf("x1", "x2", "x3"), "q", DropPosition.BEFORE)

        assertEquals(
            listOf(
                ReorderUpdate("x1", "${day}T14:00:08.000"),
                ReorderUpdate("x2", "${day}T14:00:07.000"),
                ReorderUpdate("x3", "${day}T14:00:06.000")
            ),
            result
        )
    }

    @Test
    fun `falls back to millisecond cascade when seconds-cascade collides with before-neighbor`() {
        val p = photo("p", "14:00:07")
        val q = photo("q", "14:00:05")

        val result = PhotoReorder.computeReorderDates(listOf(p, q), listOf("x1", "x2", "x3"), "q", DropPosition.BEFORE)

        assertEquals(
            listOf(
                ReorderUpdate("x1", "${day}T14:00:05.003"),
                ReorderUpdate("x2", "${day}T14:00:05.002"),
                ReorderUpdate("x3", "${day}T14:00:05.001")
            ),
            result
        )
    }

    @Test
    fun `multiple moved items with no after-neighbor cascade subtraction outward from before-neighbor`() {
        val p = photo("p", "14:00:20")

        val result = PhotoReorder.computeReorderDates(listOf(p), listOf("x1", "x2", "x3"), "p", DropPosition.AFTER)

        assertEquals(
            listOf(
                ReorderUpdate("x1", "${day}T14:00:19.000"),
                ReorderUpdate("x2", "${day}T14:00:18.000"),
                ReorderUpdate("x3", "${day}T14:00:17.000")
            ),
            result
        )
    }

    @Test
    fun `clamps results to the anchor calendar day`() {
        val q = photo("q", "23:59:59.998")

        val result = PhotoReorder.computeReorderDates(listOf(q), listOf("x1", "x2"), "q", DropPosition.BEFORE)

        assertEquals(
            listOf(
                ReorderUpdate("x1", "${day}T23:59:59.999"),
                ReorderUpdate("x2", "${day}T23:59:59.999")
            ),
            result
        )
    }

    @Test
    fun `returns empty list when target not found or moved list is empty`() {
        val p = photo("p", "14:00:10")
        assertTrue(PhotoReorder.computeReorderDates(listOf(p), listOf("x"), "non-existent", DropPosition.BEFORE).isEmpty())
        assertTrue(PhotoReorder.computeReorderDates(listOf(p), emptyList(), "p", DropPosition.BEFORE).isEmpty())
    }

    private fun fakeItem(key: String, x: Int, y: Int, w: Int = 100, h: Int = 100): LazyGridItemInfo {
        val item = io.mockk.mockk<LazyGridItemInfo>()
        io.mockk.every { item.key } returns key
        io.mockk.every { item.offset } returns IntOffset(x, y)
        io.mockk.every { item.size } returns IntSize(w, h)
        return item
    }

    @Test
    fun `findDropTarget targets closest item on same screen`() {
        val photos = listOf(photo("p1", "12:00:00"), photo("p2", "11:00:00"))
        val visible = listOf(
            fakeItem("p1", x = 0, y = 100),
            fakeItem("p2", x = 110, y = 100)
        )

        val target = PhotoReorder.findDropTarget(
            gridPos = Offset(20f, 150f),
            dayPhotos = photos,
            movedIds = setOf("p2"),
            visibleItems = visible
        )

        assertEquals("p1" to DropPosition.BEFORE, target)
    }

    @Test
    fun `findDropTarget maintains drop target at bottom edge when day continues below screen`() {
        // Day has 5 photos, only p2 and p3 are visible (p4 and p5 are below screen)
        val photos = listOf(
            photo("p1", "14:00:00"),
            photo("p2", "13:00:00"),
            photo("p3", "12:00:00"),
            photo("p4", "11:00:00"),
            photo("p5", "10:00:00")
        )
        val visible = listOf(
            fakeItem("p2", x = 0, y = 200),
            fakeItem("p3", x = 0, y = 400)
        )

        // Drag position is below p3 (e.g. at screen bottom edge y = 600)
        val target = PhotoReorder.findDropTarget(
            gridPos = Offset(50f, 600f),
            dayPhotos = photos,
            movedIds = setOf("p1"),
            visibleItems = visible
        )

        // Day continues below, so drop target is p3 with AFTER (not null!)
        assertEquals("p3" to DropPosition.AFTER, target)
    }

    @Test
    fun `findDropTarget maintains drop target at top edge when day continues above screen`() {
        // Day has 5 photos, only p3 and p4 are visible (p1 and p2 are above screen)
        val photos = listOf(
            photo("p1", "14:00:00"),
            photo("p2", "13:00:00"),
            photo("p3", "12:00:00"),
            photo("p4", "11:00:00"),
            photo("p5", "10:00:00")
        )
        val visible = listOf(
            fakeItem("p3", x = 0, y = 300),
            fakeItem("p4", x = 0, y = 500)
        )

        // Drag position is near top edge (y = 50), above p3
        val target = PhotoReorder.findDropTarget(
            gridPos = Offset(50f, 50f),
            dayPhotos = photos,
            movedIds = setOf("p5"),
            visibleItems = visible
        )

        // Day continues above, so drop target is p3 with BEFORE (not null!)
        assertEquals("p3" to DropPosition.BEFORE, target)
    }

    @Test
    fun `findDropTarget rejects drop when dragged past day boundaries if day edges are visible`() {
        // Day has 2 photos, both visible on screen
        val photos = listOf(photo("p1", "12:00:00"), photo("p2", "11:00:00"))
        val visible = listOf(
            fakeItem("p1", x = 0, y = 200),
            fakeItem("p2", x = 0, y = 400)
        )

        // Dragging far above day start (y = 50 < 200 - 50)
        val targetAbove = PhotoReorder.findDropTarget(
            gridPos = Offset(50f, 50f),
            dayPhotos = photos,
            movedIds = setOf("p2"),
            visibleItems = visible
        )
        assertNull(targetAbove)

        // Dragging far below day end (y = 600 > 500 + 50)
        val targetBelow = PhotoReorder.findDropTarget(
            gridPos = Offset(50f, 600f),
            dayPhotos = photos,
            movedIds = setOf("p1"),
            visibleItems = visible
        )
        assertNull(targetBelow)
    }
}
