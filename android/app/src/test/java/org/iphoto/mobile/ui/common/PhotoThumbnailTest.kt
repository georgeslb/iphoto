package org.iphoto.mobile.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoThumbnailTest {

    @Test
    fun `formatVideoDuration returns null for null or non-positive durations`() {
        assertNull(formatVideoDuration(null))
        assertNull(formatVideoDuration(0L))
        assertNull(formatVideoDuration(-1000L))
    }

    @Test
    fun `formatVideoDuration formats seconds under a minute`() {
        assertEquals("0:05", formatVideoDuration(5_000L))
        assertEquals("0:45", formatVideoDuration(45_000L))
    }

    @Test
    fun `formatVideoDuration formats minutes and seconds`() {
        assertEquals("1:23", formatVideoDuration(83_000L))
        assertEquals("12:05", formatVideoDuration(725_000L))
    }

    @Test
    fun `formatVideoDuration formats hours, minutes and seconds`() {
        assertEquals("1:01:05", formatVideoDuration(3_665_000L))
        assertEquals("2:15:30", formatVideoDuration(8_130_000L))
    }
}
