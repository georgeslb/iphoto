package org.iphoto.mobile.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineFastScrollbarTest {

    @Test
    fun `formatTimelineDate formats ISO yyyy-MM-dd date to dd MM yy`() {
        val formatted = formatTimelineDate("2024-03-15")
        assertEquals("15/03/24", formatted)
    }

    @Test
    fun `formatTimelineDate formats ISO datetime to dd MM yy`() {
        val formatted = formatTimelineDate("2025-11-30T14:35:00")
        assertEquals("30/11/25", formatted)
    }

    @Test
    fun `formatTimelineDate formats English long day date to dd MM yy`() {
        val formatted = formatTimelineDate("Monday, Jan 01, 2026")
        assertEquals("01/01/26", formatted)
    }

    @Test
    fun `formatTimelineDate preserves already formatted dd MM yy`() {
        assertEquals("30/11/25", formatTimelineDate("30/11/25"))
    }

    @Test
    fun `day range lookup correctly maps item indices to day strings and header indices`() {
        // Given 3 days with 2, 4, and 1 photos respectively
        val dayGroups = listOf(
            "2024-03-15" to 2, // header 0, photos 1..2 (range 0..2)
            "2024-03-14" to 4, // header 3, photos 4..7 (range 3..7)
            "2024-03-10" to 1  // header 8, photo 9     (range 8..9)
        )

        val ranges = mutableListOf<Triple<IntRange, Int, String>>()
        var curr = 0
        dayGroups.forEach { (day, count) ->
            val headerIndex = curr
            ranges.add(Triple(curr until (curr + 1 + count), headerIndex, day))
            curr += 1 + count
        }

        fun getDateForIndex(index: Int): String? {
            return ranges.firstOrNull { index in it.first }?.third ?: ranges.lastOrNull()?.third
        }

        fun getIndexForScroll(index: Int): Int {
            return ranges.firstOrNull { index in it.first }?.second ?: index
        }

        // Test date strings
        assertEquals("2024-03-15", getDateForIndex(0))
        assertEquals("2024-03-15", getDateForIndex(2))
        assertEquals("2024-03-14", getDateForIndex(3))
        assertEquals("2024-03-14", getDateForIndex(6))
        assertEquals("2024-03-10", getDateForIndex(8))
        assertEquals("2024-03-10", getDateForIndex(10))

        // Test day header index mapping (scrolling to start of day on release)
        assertEquals(0, getIndexForScroll(0))
        assertEquals(0, getIndexForScroll(1))
        assertEquals(0, getIndexForScroll(2))
        assertEquals(3, getIndexForScroll(3))
        assertEquals(3, getIndexForScroll(5))
        assertEquals(3, getIndexForScroll(7))
        assertEquals(8, getIndexForScroll(8))
        assertEquals(8, getIndexForScroll(9))
    }
}
