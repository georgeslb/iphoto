package org.iphoto.mobile.sync

import androidx.documentfile.provider.DocumentFile
import io.mockk.every
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

    // DocumentFile's own constructor is package-private to androidx.documentfile.provider, so it
    // can't be subclassed from outside that package at all (confirmed the hard way: "Cannot
    // access constructor(p0: DocumentFile?): DocumentFile - it is package-private"). MockK sidesteps
    // this entirely by allocating the instance without calling any constructor, so this wraps a
    // relaxed mock plus a plain children map instead of trying to be a real DocumentFile subtype.
    private class FakeDocumentFile(name: String) {
        val children = mutableMapOf<String, FakeDocumentFile>()
        val documentFile: DocumentFile = mockk(relaxed = true) {
            every { getName() } returns name
            every { isDirectory } returns true
            every { findFile(any()) } answers { children[firstArg<String>()]?.documentFile }
            every { createDirectory(any()) } answers {
                val childName = firstArg<String>()
                val child = FakeDocumentFile(childName)
                children[childName] = child
                child.documentFile
            }
        }
    }

    @Test
    fun `an empty relativeDir resolves to the starting directory itself`() {
        val start = FakeDocumentFile("root")

        val result = LocalTrash.resolveOrCreateDir(start.documentFile, "")

        assertSame(start.documentFile, result)
    }

    @Test
    fun `creates a single missing directory segment`() {
        val start = FakeDocumentFile("root")

        val result = LocalTrash.resolveOrCreateDir(start.documentFile, "Camera")

        assertSame(start.children["Camera"]?.documentFile, result)
    }

    @Test
    fun `creates every missing segment of a nested relativeDir, in order`() {
        val start = FakeDocumentFile("root")

        val result = LocalTrash.resolveOrCreateDir(start.documentFile, "Camera/2024/06")

        val camera = start.children["Camera"]
        assertNotNull(camera)
        val year = camera?.children?.get("2024")
        assertNotNull(year)
        val month = year?.children?.get("06")
        assertNotNull(month)
        assertSame(month?.documentFile, result)
    }

    @Test
    fun `reuses an existing directory instead of creating a duplicate`() {
        val start = FakeDocumentFile("root")
        val existingCamera = start.documentFile.createDirectory("Camera")!!

        val result = LocalTrash.resolveOrCreateDir(start.documentFile, "Camera")

        assertSame(existingCamera, result)
        assertEquals(1, start.children.size)
    }

    @Test
    fun `resolving the same nested relativeDir twice returns the same directories both times, not duplicates`() {
        val start = FakeDocumentFile("root")

        val first = LocalTrash.resolveOrCreateDir(start.documentFile, "Camera/2024/06")
        val second = LocalTrash.resolveOrCreateDir(start.documentFile, "Camera/2024/06")

        assertSame(first, second)
        assertEquals(1, start.children.size)
        assertEquals(1, start.children.getValue("Camera").children.size)
    }
}
