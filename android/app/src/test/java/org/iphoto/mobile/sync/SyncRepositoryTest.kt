package org.iphoto.mobile.sync

import android.content.Context
import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.iphoto.mobile.model.SpringPage
import org.iphoto.mobile.model.SpringPageInfo
import org.iphoto.mobile.network.PhotoApi
import org.iphoto.mobile.network.TaskApi
import org.iphoto.mobile.testutil.testPhoto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val SPACE_ID = "space-1"

/**
 * Exercises SyncRepository's own diff/reconciliation logic (computeDiff and the local-trash
 * helpers) without touching real SAF/DocumentFile machinery: LocalFolderScanner and LocalTrash are
 * both plain Kotlin objects, so `mockkObject` can stub their (non-suspend) functions directly,
 * sidestepping the need to fake a whole DocumentFile tree just to test SyncRepository's own logic.
 * `HashCache.get` is stubbed per-asset (keyed on each asset's unique size, since `Uri.toString()`
 * can't itself be stubbed on a mock - see `nextAssetSize`) so `hashOf` never falls through to the
 * real FileHasher/ContentResolver path either - `context` here is never genuinely exercised, only
 * threaded through as an opaque argument the mocked LocalFolderScanner/LocalTrash calls match on.
 */
class SyncRepositoryTest {

    private val context = mockk<Context>(relaxed = true)
    private val photoApi = mockk<PhotoApi>()
    private val taskApi = mockk<TaskApi>()
    private val hashCache = mockk<HashCache>(relaxed = true)
    private val folderUri = mockk<Uri>(relaxed = true)

    private lateinit var repository: SyncRepository

    @Before
    fun setUp() {
        mockkObject(LocalFolderScanner)
        mockkObject(LocalTrash)
        repository = SyncRepository(context, photoApi, taskApi, hashCache)
    }

    @After
    fun tearDown() {
        unmockkObject(LocalFolderScanner)
        unmockkObject(LocalTrash)
    }

    // Each asset gets a distinct size so hashCache.get's (size, lastModified) pair uniquely
    // identifies it, without needing Uri.toString() to return anything meaningful (mocks can't be
    // stubbed for toString via the normal every {} API).
    private var nextAssetSize = 100L

    private fun testAsset(hash: String, name: String = "asset-$hash.jpg", relativeDir: String = ""): LocalAsset {
        val size = nextAssetSize++
        val asset = LocalAsset(
            uri = mockk(relaxed = true),
            displayName = name,
            size = size,
            lastModified = 1_000L,
            mimeType = "image/jpeg",
            relativeDir = relativeDir
        )
        every { hashCache.get(any(), size, 1_000L) } returns hash
        return asset
    }

    private fun onePage(photos: List<org.iphoto.mobile.model.Photo>) =
        SpringPage(content = photos, page = SpringPageInfo(size = 50, number = 0, totalElements = photos.size.toLong(), totalPages = 1))

    private fun stubEmptySpace() {
        every { LocalFolderScanner.scan(context, folderUri) } returns emptyList()
        every { LocalTrash.scan(context, folderUri) } returns emptyList()
        coEvery { photoApi.listForSpace(SPACE_ID, 0, any()) } returns onePage(emptyList())
        coEvery { photoApi.listGarbageForSpace(SPACE_ID, 0, any()) } returns onePage(emptyList())
    }

    @Test
    fun `a local file with no matching remote hash is queued for upload`() = runTest {
        stubEmptySpace()
        val newAsset = testAsset("hash-new")
        every { LocalFolderScanner.scan(context, folderUri) } returns listOf(newAsset)

        val diff = repository.computeDiff(SPACE_ID, folderUri)

        assertEquals(listOf(newAsset), diff.toUpload)
        assertTrue(diff.toRemove.isEmpty())
        assertTrue(diff.toRestore.isEmpty())
        assertTrue(diff.toLocalRestore.isEmpty())
    }

    @Test
    fun `a remote photo whose file is gone locally is queued for removal`() = runTest {
        stubEmptySpace()
        coEvery { photoApi.listForSpace(SPACE_ID, 0, any()) } returns onePage(listOf(testPhoto("p1", fileHash = "hash-gone")))

        val diff = repository.computeDiff(SPACE_ID, folderUri)

        assertEquals(listOf(RemotePhotoRef("p1", "hash-gone")), diff.toRemove)
    }

    @Test
    fun `a remote photo with no recorded hash is never queued for removal`() = runTest {
        stubEmptySpace()
        coEvery { photoApi.listForSpace(SPACE_ID, 0, any()) } returns onePage(listOf(testPhoto("p1", fileHash = null)))

        val diff = repository.computeDiff(SPACE_ID, folderUri)

        assertTrue(diff.toRemove.isEmpty())
    }

    @Test
    fun `a local file matching a garbage photo's hash is restored instead of re-uploaded`() = runTest {
        stubEmptySpace()
        val reappearedAsset = testAsset("hash-garbage")
        every { LocalFolderScanner.scan(context, folderUri) } returns listOf(reappearedAsset)
        coEvery { photoApi.listGarbageForSpace(SPACE_ID, 0, any()) } returns
            onePage(listOf(testPhoto("garbage-1", fileHash = "hash-garbage")))

        val diff = repository.computeDiff(SPACE_ID, folderUri)

        assertEquals(listOf(RemotePhotoRef("garbage-1", "hash-garbage")), diff.toRestore)
        assertTrue("a restored photo should not also be queued for upload", diff.toUpload.isEmpty())
    }

    @Test
    fun `a trashed file matching an active remote photo is restored locally`() = runTest {
        stubEmptySpace()
        val trashedAsset = testAsset("hash-active")
        every { LocalTrash.scan(context, folderUri) } returns listOf(trashedAsset)
        coEvery { photoApi.listForSpace(SPACE_ID, 0, any()) } returns
            onePage(listOf(testPhoto("active-1", fileHash = "hash-active")))

        val diff = repository.computeDiff(SPACE_ID, folderUri)

        assertEquals(listOf(trashedAsset), diff.toLocalRestore)
        assertTrue("a still-active remote photo should never be queued for removal", diff.toRemove.isEmpty())
    }

    @Test
    fun `pages through every page of both active and garbage photos`() = runTest {
        stubEmptySpace()
        coEvery { photoApi.listForSpace(SPACE_ID, 0, any()) } returns
            SpringPage(listOf(testPhoto("p1", fileHash = "h1")), SpringPageInfo(50, 0, 2, 2))
        coEvery { photoApi.listForSpace(SPACE_ID, 1, any()) } returns
            SpringPage(listOf(testPhoto("p2", fileHash = "h2")), SpringPageInfo(50, 1, 2, 2))

        val diff = repository.computeDiff(SPACE_ID, folderUri)

        assertEquals(setOf("p1", "p2"), diff.toRemove.map { it.id }.toSet())
    }

    @Test
    fun `computeDiff persists the hash cache`() = runTest {
        stubEmptySpace()

        repository.computeDiff(SPACE_ID, folderUri)

        verify(atLeast = 1) { hashCache.persist() }
    }

    @Test
    fun `removeFromSpace is a no-op with nothing to remove`() = runTest {
        repository.removeFromSpace(emptyList())

        coVerify(exactly = 0) { photoApi.moveToGarbage(any()) }
    }

    @Test
    fun `removeFromSpace moves the given ids to garbage`() = runTest {
        coEvery { photoApi.moveToGarbage(listOf("p1")) } returns Unit

        repository.removeFromSpace(listOf("p1"))

        coVerify(exactly = 1) { photoApi.moveToGarbage(listOf("p1")) }
    }

    @Test
    fun `restoreInSpace is a no-op with nothing to restore`() = runTest {
        repository.restoreInSpace(emptyList())

        coVerify(exactly = 0) { photoApi.restoreFromGarbage(any()) }
    }

    @Test
    fun `restoreInSpace restores the given ids`() = runTest {
        coEvery { photoApi.restoreFromGarbage(listOf("p1")) } returns Unit

        repository.restoreInSpace(listOf("p1"))

        coVerify(exactly = 1) { photoApi.restoreFromGarbage(listOf("p1")) }
    }

    @Test
    fun `trashLocalFilesForHashes only trashes files matching the given hashes`() = runTest {
        val matching = testAsset("hash-match")
        val other = testAsset("hash-other")
        every { LocalFolderScanner.scan(context, folderUri) } returns listOf(matching, other)
        every { LocalTrash.moveToTrash(context, folderUri, any()) } returns true

        repository.trashLocalFilesForHashes(folderUri, setOf("hash-match"))

        verify(exactly = 1) { LocalTrash.moveToTrash(context, folderUri, matching) }
        verify(exactly = 0) { LocalTrash.moveToTrash(context, folderUri, other) }
    }

    @Test
    fun `trashLocalFilesForHashes forwards a nested file's relativeDir unchanged`() = runTest {
        val nested = testAsset("hash-nested", relativeDir = "Camera/2024/06")
        every { LocalFolderScanner.scan(context, folderUri) } returns listOf(nested)
        every { LocalTrash.moveToTrash(context, folderUri, any()) } returns true

        repository.trashLocalFilesForHashes(folderUri, setOf("hash-nested"))

        // LocalTrash.resolveOrCreateDir (LocalTrashTest) is what actually mirrors this path under
        // .iPhotoTrash - this just guards against SyncRepository itself ever stripping it on the
        // way there.
        verify(exactly = 1) { LocalTrash.moveToTrash(context, folderUri, match { it.relativeDir == "Camera/2024/06" }) }
    }

    @Test
    fun `trashLocalFilesForHashes is a no-op with no hashes to match`() = runTest {
        repository.trashLocalFilesForHashes(folderUri, emptySet())

        verify(exactly = 0) { LocalFolderScanner.scan(any(), any()) }
    }

    @Test
    fun `restoreLocalAssets moves every given asset back out of the trash`() = runTest {
        val asset1 = testAsset("hash-1")
        val asset2 = testAsset("hash-2")
        every { LocalTrash.restoreFromTrash(context, folderUri, any()) } returns true

        repository.restoreLocalAssets(folderUri, listOf(asset1, asset2))

        verify(exactly = 1) { LocalTrash.restoreFromTrash(context, folderUri, asset1) }
        verify(exactly = 1) { LocalTrash.restoreFromTrash(context, folderUri, asset2) }
    }
}
