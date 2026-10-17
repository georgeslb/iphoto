package org.iphoto.mobile.data

import android.net.Uri
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.iphoto.mobile.testutil.FakeSharedPreferences
import org.iphoto.mobile.ui.sync.SelectedFolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncPrefsTest {

    private fun newPrefs(prefs: FakeSharedPreferences = FakeSharedPreferences()): SyncPrefs = SyncPrefs(prefs)

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            val uriString = firstArg<String>()
            val uriMock = io.mockk.mockk<Uri>()
            every { uriMock.toString() } returns uriString
            uriMock
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun `starts with empty folders and uploaded assets`() {
        val syncPrefs = newPrefs()

        assertTrue(syncPrefs.loadSelectedFolders().isEmpty())
        assertTrue(syncPrefs.getUploadedAssetKeys().isEmpty())
    }

    @Test
    fun `save and load selected folders round trip`() {
        val syncPrefs = newPrefs()
        val uri1 = Uri.parse("content://media/external/folder1")
        val uri2 = Uri.parse("content://media/external/folder2")
        val folders = listOf(
            SelectedFolder(uri1, "Camera"),
            SelectedFolder(uri2, "Screenshots")
        )

        syncPrefs.saveSelectedFolders(folders)
        val loaded = syncPrefs.loadSelectedFolders()

        assertEquals(2, loaded.size)
        assertEquals(setOf("Camera", "Screenshots"), loaded.map { it.name }.toSet())
    }

    @Test
    fun `record and get uploaded assets round trip`() {
        val syncPrefs = newPrefs()

        syncPrefs.recordUploadedAsset("content://media/1", "DCIM", "photo1.jpg")
        syncPrefs.recordUploadedAsset("content://media/1", "DCIM", "photo2.jpg")

        val keys = syncPrefs.getUploadedAssetKeys()
        assertEquals(2, keys.size)
        val expectedKey = SyncPrefs.encodeUploadedKey("content://media/1", "DCIM", "photo1.jpg")
        assertTrue(keys.contains(expectedKey))
    }

    @Test
    fun `removeUploadedAsset removes the deleted asset key from preferences`() {
        val syncPrefs = newPrefs()
        every { Uri.decode(any()) } answers { firstArg() }

        syncPrefs.recordUploadedAsset("content://media/1", "DCIM", "photo1.jpg")
        syncPrefs.recordUploadedAsset("content://media/1", "DCIM", "photo2.jpg")
        syncPrefs.removeUploadedAsset("content://media/1", "DCIM", "photo1.jpg")

        val keys = syncPrefs.getUploadedAssetKeys()
        assertEquals(1, keys.size)
        assertTrue(keys.contains(SyncPrefs.encodeUploadedKey("content://media/1", "DCIM", "photo2.jpg")))
    }

    @Test
    fun `retainUploadedAssetsForFolder prunes missing files while preserving existing ones and other folders`() {
        val syncPrefs = newPrefs()
        every { Uri.decode(any()) } answers { firstArg() }

        syncPrefs.recordUploadedAsset("content://media/1", "DCIM", "photo1.jpg")
        syncPrefs.recordUploadedAsset("content://media/1", "DCIM", "deleted.jpg")
        syncPrefs.recordUploadedAsset("content://media/2", "Pictures", "other.jpg")

        syncPrefs.retainUploadedAssetsForFolder("content://media/1", setOf("DCIM" to "photo1.jpg"))

        val keys = syncPrefs.getUploadedAssetKeys()
        assertEquals(2, keys.size)
        assertTrue(keys.contains(SyncPrefs.encodeUploadedKey("content://media/1", "DCIM", "photo1.jpg")))
        assertTrue(keys.contains(SyncPrefs.encodeUploadedKey("content://media/2", "Pictures", "other.jpg")))
    }

    @Test
    fun `applyBatchHashCheckStatuses marks existing hashes as uploaded and removes non-existing ones`() {
        val syncPrefs = newPrefs()
        every { Uri.decode(any()) } answers { firstArg() }

        // Previously marked as uploaded, now server says false
        syncPrefs.recordUploadedAsset("content://media/1", "DCIM", "stale.jpg")

        syncPrefs.applyBatchHashCheckStatuses(
            folderUri = "content://media/1",
            statuses = listOf(
                ("DCIM" to "on_server.jpg") to true,
                ("DCIM" to "stale.jpg") to false,
                ("DCIM" to "never_uploaded.jpg") to false
            )
        )

        val keys = syncPrefs.getUploadedAssetKeys()
        assertEquals(1, keys.size)
        assertTrue(keys.contains(SyncPrefs.encodeUploadedKey("content://media/1", "DCIM", "on_server.jpg")))
    }
}
