package org.iphoto.mobile.sync

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Directly exercises LocalTrash.resolveOrCreateDir - the path-walking/creation logic behind the
 * trash/restore subfolder fix (see android/TODO.md) - against a small hand-built in-memory
 * DocumentFile tree, rather than a mocked-away collaborator. moveToTrash/restoreFromTrash
 * themselves aren't covered here: they also depend on DocumentFile.fromTreeUri/fromSingleUri's
 * static resolution and ContentResolver-based byte copying, which would need a much larger fake
 * SAF harness to exercise for real - resolveOrCreateDir is the part of this fix actually worth
 * that investment, since it's where a path-splitting or directory-reuse mistake would live.
 */
class LocalTrashTest {

    private class FakeDocumentFile(override val name: String) : DocumentFile() {
        val children = mutableMapOf<String, FakeDocumentFile>()
        override val uri: Uri get() = mockk(relaxed = true)
        override val type: String? = null
        override val isDirectory: Boolean = true
        override val isFile: Boolean = false
        override fun listFiles(): Array<DocumentFile> = children.values.toTypedArray()
        override fun length(): Long = 0
        override fun lastModified(): Long = 0
        override fun exists(): Boolean = true
        override fun delete(): Boolean = true
        override fun findFile(displayName: String): DocumentFile? = children[displayName]
        override fun createDirectory(displayName: String): DocumentFile? {
            val child = FakeDocumentFile(displayName)
            children[displayName] = child
            return child
        }
        override fun createFile(mimeType: String, displayName: String): DocumentFile? = null
    }

    @Test
    fun `an empty relativeDir resolves to the starting directory itself`() {
        val start = FakeDocumentFile("root")

        val result = LocalTrash.resolveOrCreateDir(start, "")

        assertSame(start, result)
    }

    @Test
    fun `creates a single missing directory segment`() {
        val start = FakeDocumentFile("root")

        val result = LocalTrash.resolveOrCreateDir(start, "Camera")

        assertSame(start.children["Camera"], result)
    }

    @Test
    fun `creates every missing segment of a nested relativeDir, in order`() {
        val start = FakeDocumentFile("root")

        val result = LocalTrash.resolveOrCreateDir(start, "Camera/2024/06")

        val camera = start.children["Camera"]
        assertNotNull(camera)
        val year = camera?.children?.get("2024")
        assertNotNull(year)
        val month = year?.children?.get("06")
        assertNotNull(month)
        assertSame(month, result)
    }

    @Test
    fun `reuses an existing directory instead of creating a duplicate`() {
        val start = FakeDocumentFile("root")
        val existingCamera = start.createDirectory("Camera")!!

        val result = LocalTrash.resolveOrCreateDir(start, "Camera")

        assertSame(existingCamera, result)
        assertEquals(1, start.children.size)
    }

    @Test
    fun `resolving the same nested relativeDir twice returns the same directories both times, not duplicates`() {
        val start = FakeDocumentFile("root")

        val first = LocalTrash.resolveOrCreateDir(start, "Camera/2024/06")
        val second = LocalTrash.resolveOrCreateDir(start, "Camera/2024/06")

        assertSame(first, second)
        assertEquals(1, start.children.size)
        assertEquals(1, start.children.getValue("Camera").children.size)
    }
}
