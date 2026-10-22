package org.iphoto.mobile.ui.common

import org.iphoto.mobile.model.Photo
import org.junit.Assert.assertEquals
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
}
