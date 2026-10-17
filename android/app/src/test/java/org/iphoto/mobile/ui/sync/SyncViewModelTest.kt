package org.iphoto.mobile.ui.sync

import android.content.Context
import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.iphoto.mobile.data.SyncPrefs
import org.iphoto.mobile.model.TaskDTO
import org.iphoto.mobile.network.SpaceApi
import org.iphoto.mobile.sync.LocalAsset
import org.iphoto.mobile.sync.LocalFolderScanner
import org.iphoto.mobile.sync.SyncRepository
import org.iphoto.mobile.sync.UploadForegroundService
import org.iphoto.mobile.testutil.FakeSharedPreferences
import org.iphoto.mobile.testutil.MainDispatcherRule
import org.iphoto.mobile.testutil.testSpace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SyncViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var spaceApi: SpaceApi
    private lateinit var syncRepository: SyncRepository
    private lateinit var syncPrefs: SyncPrefs

    private val uriCache = mutableMapOf<String, Uri>()

    @Before
    fun setUp() {
        uriCache.clear()
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            val raw = firstArg<String>()
            uriCache.getOrPut(raw) {
                mockk<Uri> { every { this@mockk.toString() } returns raw }
            }
        }
        every { Uri.decode(any()) } answers { firstArg() }

        mockkObject(UploadForegroundService)
        every { UploadForegroundService.start(any()) } returns Unit
        every { UploadForegroundService.stop(any()) } returns Unit

        mockkObject(LocalFolderScanner)

        context = mockk(relaxed = true)
        spaceApi = mockk()
        syncRepository = mockk(relaxed = true)
        syncPrefs = SyncPrefs(FakeSharedPreferences())

        coEvery { spaceApi.list() } returns listOf(testSpace("space-1"))
    }

    @After
    fun tearDown() {
        unmockkObject(LocalFolderScanner)
        unmockkObject(UploadForegroundService)
        unmockkStatic(Uri::class)
    }

    private fun fakeAsset(uriStr: String, name: String, relativeDir: String = ""): LocalAsset =
        LocalAsset(
            uri = Uri.parse(uriStr),
            displayName = name,
            size = 1024L,
            lastModified = 1_700_000_000_000L,
            mimeType = "image/jpeg",
            relativeDir = relativeDir
        )

    @Test
    fun `UploadVerificationResult summaryMessage matches Web UI status messages for single and batch uploads`() {
        val a1 = fakeAsset("content://1", "a1.jpg")
        val a2 = fakeAsset("content://2", "a2.jpg")
        val a3 = fakeAsset("content://3", "a3.jpg")

        assertEquals(
            "Already imported",
            UploadVerificationResult(alreadyImported = listOf(a1)).summaryMessage(singleAsset = true)
        )
        assertEquals(
            "Already imported",
            UploadVerificationResult(alreadyImported = listOf(a1)).summaryMessage(singleAsset = false)
        )
        assertEquals(
            "Already imported and in trash",
            UploadVerificationResult(alreadyInTrash = listOf(a1)).summaryMessage(singleAsset = true)
        )
        assertEquals(
            "Uploaded successfully",
            UploadVerificationResult(uploaded = listOf(a1)).summaryMessage(singleAsset = true)
        )
        assertEquals(
            "Uploaded 1 photo(s), 1 already imported, 1 already imported and in trash",
            UploadVerificationResult(
                uploaded = listOf(a1),
                alreadyImported = listOf(a2),
                alreadyInTrash = listOf(a3)
            ).summaryMessage(singleAsset = false)
        )
    }

    @Test
    fun `uploadAndVerify categorizes COMPLETED, IGNORED, and TRASHED tasks and records uploaded keys`() = runTest {
        val folderUri = Uri.parse("content://folder/camera")
        val aCompleted = fakeAsset("content://folder/camera/1", "new.jpg")
        val aIgnored = fakeAsset("content://folder/camera/2", "dup.jpg")
        val aTrashed = fakeAsset("content://folder/camera/3", "trashed.jpg")

        coEvery {
            syncRepository.uploadAsset(aCompleted, "space-1", any(), any())
        } returns TaskDTO(id = "t1", status = "COMPLETED", photoId = "p1")
        coEvery {
            syncRepository.uploadAsset(aIgnored, "space-1", any(), any())
        } returns TaskDTO(id = "t2", status = "IGNORED", photoId = null)
        coEvery {
            syncRepository.uploadAsset(aTrashed, "space-1", any(), any())
        } returns TaskDTO(id = "t3", status = "TRASHED", photoId = null)

        val vm = SyncViewModel(context, spaceApi, syncRepository, syncPrefs)
        val result = vm.uploadAndVerify(
            assets = listOf(aCompleted, aIgnored, aTrashed),
            spaceId = "space-1",
            targetFolderUri = folderUri
        )

        assertEquals(listOf(aCompleted), result.uploaded)
        assertEquals(listOf(aIgnored), result.alreadyImported)
        assertEquals(listOf(aTrashed), result.alreadyInTrash)
        assertEquals(3, result.size)

        assertTrue(vm.isAssetUploaded(folderUri, aCompleted))
        assertTrue(vm.isAssetUploaded(folderUri, aIgnored))
        assertTrue(vm.isAssetUploaded(folderUri, aTrashed))
    }

    @Test
    fun `deleting or trashing a local asset removes its uploaded cloud indicator state on the phone`() = runTest {
        val folderUri = Uri.parse("content://folder/camera")
        val asset1 = fakeAsset("content://folder/camera/1", "photo1.jpg")
        val asset2 = fakeAsset("content://folder/camera/2", "photo2.jpg")

        every { LocalFolderScanner.scan(context, folderUri) } returns listOf(asset1, asset2)
        syncPrefs.recordUploadedAsset(folderUri.toString(), "", "photo1.jpg")
        syncPrefs.recordUploadedAsset(folderUri.toString(), "", "photo2.jpg")

        val vm = SyncViewModel(context, spaceApi, syncRepository, syncPrefs)
        vm.addFolder(folderUri, "Camera")

        assertTrue(vm.isAssetUploaded(folderUri, asset1))
        assertTrue(vm.isAssetUploaded(folderUri, asset2))

        // Simulate asset1 being trashed on the device and disappearing from the folder scan
        every { LocalFolderScanner.scan(context, folderUri) } returns listOf(asset2)
        vm.trashSingleAsset(asset1)

        assertFalse(vm.isAssetUploaded(folderUri, asset1))
        assertTrue(vm.isAssetUploaded(folderUri, asset2))
        io.mockk.verify(exactly = 1) { syncRepository.removeCachedHashesForAssets(listOf(asset1)) }
    }

    @Test
    fun `refreshSpaces reloads spaces list from SpaceApi`() = runTest {
        val vm = SyncViewModel(context, spaceApi, syncRepository, syncPrefs)
        assertEquals(listOf("space-1"), vm.uiState.value.spaces.map { it.id })

        coEvery { spaceApi.list() } returns listOf(testSpace("space-1"), testSpace("space-2"))
        vm.refreshSpaces()

        assertEquals(listOf("space-1", "space-2"), vm.uiState.value.spaces.map { it.id })
        coVerify(exactly = 2) { spaceApi.list() }
    }

    @Test
    fun `startup background MD5 check updates uploadedAssetKeys when server check_hashes returns true`() = runTest {
        val folderUri = Uri.parse("content://folder/camera")
        val asset1 = fakeAsset("content://folder/camera/1", "web_uploaded.jpg")
        val asset2 = fakeAsset("content://folder/camera/2", "local_only.jpg")

        syncPrefs.saveSelectedFolders(listOf(SelectedFolder(folderUri, "Camera")))
        every { LocalFolderScanner.scan(context, folderUri) } returns listOf(asset1, asset2)

        coEvery {
            syncRepository.verifyFolderAssetsHashesInBatches(
                assets = any(),
                batchSize = SyncRepository.HASH_CHECK_BATCH_SIZE,
                onBatchVerified = any()
            )
        } coAnswers {
            val callback = thirdArg<suspend (List<Pair<LocalAsset, Boolean>>) -> Unit>()
            callback(listOf(asset1 to true, asset2 to false))
        }

        val vm = SyncViewModel(
            context,
            spaceApi,
            syncRepository,
            syncPrefs,
            ioDispatcher = mainDispatcherRule.testDispatcher
        )

        assertTrue(vm.isAssetUploaded(folderUri, asset1))
        assertFalse(vm.isAssetUploaded(folderUri, asset2))
    }

    @Test
    fun `refreshFolder does not set isLoadingFolderContents when folder contents are already loaded`() = runTest {
        val folderUri = Uri.parse("content://folder/camera")
        val asset1 = fakeAsset("content://folder/camera/1", "photo1.jpg")
        syncPrefs.saveSelectedFolders(listOf(SelectedFolder(folderUri, "Camera")))
        every { LocalFolderScanner.scan(context, folderUri) } returns listOf(asset1)

        val vm = SyncViewModel(
            context,
            spaceApi,
            syncRepository,
            syncPrefs,
            ioDispatcher = mainDispatcherRule.testDispatcher
        )

        assertTrue(vm.uiState.value.folderContents.containsKey(folderUri))
        assertFalse(vm.uiState.value.isLoadingFolderContents.contains(folderUri))

        var wasLoadingDuringScan = false
        every { LocalFolderScanner.scan(context, folderUri) } answers {
            wasLoadingDuringScan = vm.uiState.value.isLoadingFolderContents.contains(folderUri)
            listOf(asset1)
        }

        vm.refreshFolder(folderUri, "Camera")

        assertFalse(wasLoadingDuringScan)
        assertTrue(vm.uiState.value.folderContents.containsKey(folderUri))
    }
}
