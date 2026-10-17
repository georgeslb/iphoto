package org.iphoto.mobile.sync

import android.net.Uri
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditedMediaRelocatorTest {

    private val launchedAt = 1_800_000_000L

    private fun snapshot(
        relativePath: String? = "DCIM/Camera/",
        editorPackage: String? = "com.google.android.apps.photos",
        isVideo: Boolean = false
    ) = EditSnapshot(
        originalUri = mockk<Uri>(),
        collection = mockk<Uri>(),
        originalId = 42L,
        relativePath = relativePath,
        dateTakenMs = 1_600_000_000_000L,
        dateModifiedSec = 1_600_000_000L,
        sizeBytes = 2048L,
        fileLastModifiedMs = 1_600_000_000_000L,
        launchedAtSec = launchedAt,
        isVideo = isVideo,
        editorPackage = editorPackage,
        exifAttributes = emptyMap()
    )

    private fun row(
        id: Long = 99L,
        dateAdded: Long = launchedAt + 5,
        owner: String? = "com.google.android.apps.photos",
        mime: String? = "image/jpeg",
        dateModified: Long = launchedAt + 5,
        size: Long = 4096L,
        relativePath: String? = "Pictures/Edited/"
    ) = MediaRow(id, relativePath, dateAdded, dateModified, size, owner, mime)

    @Test
    fun `new file written by the launched editor after launch is detected as edit output`() {
        assertTrue(EditedMediaRelocator.isNewEditorOutput(row(), snapshot()))
    }

    @Test
    fun `media created before launch, by another app, or of another type is ignored`() {
        val snap = snapshot()
        assertFalse(EditedMediaRelocator.isNewEditorOutput(row(dateAdded = launchedAt - 60), snap))
        assertFalse(EditedMediaRelocator.isNewEditorOutput(row(owner = "com.android.camera"), snap))
        assertFalse(EditedMediaRelocator.isNewEditorOutput(row(mime = "video/mp4"), snap))
        assertFalse(EditedMediaRelocator.isNewEditorOutput(row(id = 42L), snap))
    }

    @Test
    fun `unknown owner package is accepted`() {
        assertTrue(EditedMediaRelocator.isNewEditorOutput(row(owner = null), snapshot()))
    }

    @Test
    fun `original overwritten in place is detected by size or modification change`() {
        val snap = snapshot()
        assertTrue(EditedMediaRelocator.isOriginalOverwritten(row(id = 42L, size = 9999L), snap))
        assertFalse(
            EditedMediaRelocator.isOriginalOverwritten(
                row(id = 42L, size = 2048L, dateModified = 1_600_000_000L), snap
            )
        )
    }

    @Test
    fun `edited copy saved elsewhere is moved back to the original folder with original dates`() {
        val output = EditedOutput(mockk(), "Pictures/Edited/", overwritesOriginal = false)
        val fix = EditedMediaRelocator.planFix(snapshot(), output)
        assertTrue(fix.needsMove)
        assertEquals("DCIM/Camera/", fix.targetRelativePath)
        assertEquals(1_600_000_000_000L, fix.dateTakenMs)
        assertEquals(1_600_000_000_000L, fix.lastModifiedMs)
    }

    @Test
    fun `edited copy already in the original folder or original overwritten is not moved`() {
        val sameFolder = EditedOutput(mockk(), "dcim/camera", overwritesOriginal = false)
        assertFalse(EditedMediaRelocator.planFix(snapshot(), sameFolder).needsMove)

        val inPlace = EditedOutput(mockk(), "DCIM/Camera/", overwritesOriginal = true)
        val fix = EditedMediaRelocator.planFix(snapshot(), inPlace)
        assertNull(fix.targetRelativePath)
        assertEquals(1_600_000_000_000L, fix.lastModifiedMs)
    }
}
