package org.iphoto.mobile.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineFastScrollbarTest {

    @Test
    fun `formatTimelineDate formats ISO yyyy-MM-dd date`() {
        val formatted = formatTimelineDate("2024-03-15")
        assertTrue(formatted.contains("15"))
        assertTrue(formatted.contains("2024"))
    }

    @Test
    fun `formatTimelineDate returns raw string when not ISO date`() {
        val raw = "Monday, Jan 01, 2026"
        assertEquals(raw, formatTimelineDate(raw))
    }

    @Test
    fun `day range lookup correctly maps item indices to day strings`() {
        // Given 3 days with 2, 4, and 1 photos respectively
        val dayGroups = listOf(
            "2024-03-15" to 2, // header 0, photos 1..2 (range 0..2)
            "2024-03-14" to 4, // header 3, photos 4..7 (range 3..7)
            "2024-03-10" to 1  // header 8, photo 9     (range 8..9)
        )

        val ranges = mutableListOf<Pair<IntRange, String>>()
        var curr = 0
        dayGroups.forEach { (day, count) ->
            ranges.add(curr until (curr + 1 + count) to day)
            curr += 1 + count
        }

        fun getDateForIndex(index: Int): String? {
            return ranges.firstOrNull { index in it.first }?.second ?: ranges.lastOrNull()?.second
        }

        // Test index 0 (header of day 1)
        assertEquals("2024-03-15", getDateForIndex(0))
        // Test index 2 (last photo of day 1)
        assertEquals("2024-03-15", getDateForIndex(2))
        // Test index 3 (header of day 2)
        assertEquals("2024-03-14", getDateForIndex(3))
        // Test index 6 (photo in day 2)
        assertEquals("2024-03-14", getDateForIndex(6))
        // Test index 8 (header of day 3)
        assertEquals("2024-03-10", getDateForIndex(8))
        // Test index 10 (past the end, e.g. load-more) -> falls back to last day
        assertEquals("2024-03-10", getDateForIndex(10))
    }
}
