package org.iphoto.mobile.ui.shares

import android.net.Uri
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.iphoto.mobile.model.ShareResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SharesScreenTest {

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.encode(any()) } answers {
            val raw = firstArg<String>()
            java.net.URLEncoder.encode(raw, "UTF-8").replace("+", "%20")
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun `buildShareUrl formats complete URL with server base and encoded name`() {
        val share = ShareResponse(
            id = "share-123",
            name = "Vacances Été 2026",
            albumId = "album-456",
            isPasswordProtected = false,
            expired = false,
            userMd5 = "abc123md5"
        )

        val url = buildShareUrl("https://photos.example.com/", share)
        assertEquals("https://photos.example.com/share/abc123md5/Vacances%20%C3%89t%C3%A9%202026", url)
    }

    @Test
    fun `buildShareUrl handles serverUrl without trailing slash`() {
        val share = ShareResponse(
            id = "share-123",
            name = "Trip",
            albumId = "album-456",
            isPasswordProtected = true,
            expired = false,
            userMd5 = "userhash"
        )

        val url = buildShareUrl("http://192.168.1.50:8080", share)
        assertEquals("http://192.168.1.50:8080/share/userhash/Trip", url)
    }
}
