package org.iphoto.mobile.ui.photo

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.iphoto.mobile.model.AlbumDTO
import org.iphoto.mobile.model.AlbumPhotoRequest
import org.iphoto.mobile.model.Photo
import org.iphoto.mobile.network.AlbumApi
import org.iphoto.mobile.network.PhotoApi
import org.iphoto.mobile.testutil.MainDispatcherRule
import org.iphoto.mobile.testutil.testPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PhotoDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `loads the photo on init`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")

        val viewModel = PhotoDetailViewModel(photoApi, "1", ioDispatcher = mainDispatcherRule.testDispatcher)

        val state = viewModel.uiState.value as PhotoDetailUiState.Loaded
        assertEquals("1", state.photo.id)
        assertFalse(state.isMutating)
    }

    @Test
    fun `falls back to Failed when loading throws`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } throws RuntimeException("not found")

        val viewModel = PhotoDetailViewModel(photoApi, "1", ioDispatcher = mainDispatcherRule.testDispatcher)

        val state = viewModel.uiState.value
        assertTrue(state is PhotoDetailUiState.Failed)
        assertEquals("not found", (state as PhotoDetailUiState.Failed).message)
    }

    @Test
    fun `moveToGarbage transitions to Removed on success`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        coEvery { photoApi.moveToGarbage(listOf("1")) } returns Unit
        val viewModel = PhotoDetailViewModel(photoApi, "1", ioDispatcher = mainDispatcherRule.testDispatcher)

        viewModel.moveToGarbage()

        assertTrue(viewModel.uiState.value is PhotoDetailUiState.Removed)
    }

    @Test
    fun `moveToGarbage surfaces the error and stays Loaded on failure`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        coEvery { photoApi.moveToGarbage(any()) } throws RuntimeException("boom")
        val viewModel = PhotoDetailViewModel(photoApi, "1", ioDispatcher = mainDispatcherRule.testDispatcher)

        viewModel.moveToGarbage()

        val state = viewModel.uiState.value as PhotoDetailUiState.Loaded
        assertEquals("boom", state.actionError)
        assertFalse(state.isMutating)
    }

    @Test
    fun `moveToMain calls the api and transitions to Removed`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        coEvery { photoApi.moveToMain(listOf("1")) } returns Unit
        val viewModel = PhotoDetailViewModel(photoApi, "1", canMoveToMain = true)

        viewModel.moveToMain(photoApi)

        coVerify(exactly = 1) { photoApi.moveToMain(listOf("1")) }
        assertTrue(viewModel.uiState.value is PhotoDetailUiState.Removed)
    }

    @Test
    fun `moveToMain surfaces the error and stays Loaded on failure`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        coEvery { photoApi.moveToMain(any()) } throws RuntimeException("boom")
        val viewModel = PhotoDetailViewModel(photoApi, "1", canMoveToMain = true)

        viewModel.moveToMain(photoApi)

        val state = viewModel.uiState.value as PhotoDetailUiState.Loaded
        assertEquals("boom", state.actionError)
    }

    @Test
    fun `removeFromAlbum calls the api with this photo's id, notifies onAlbumsChanged, and transitions to Removed`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        val albumApi = mockk<AlbumApi>()
        coEvery { albumApi.removePhotos("album-1", AlbumPhotoRequest(listOf("1"))) } returns
            AlbumDTO("album-1", "Trip", null, null, 0, "now", "now")
        val viewModel = PhotoDetailViewModel(photoApi, "1", albumId = "album-1")
        var albumsChangedCount = 0

        viewModel.removeFromAlbum(albumApi, onAlbumsChanged = { albumsChangedCount++ })

        coVerify(exactly = 1) { albumApi.removePhotos("album-1", AlbumPhotoRequest(listOf("1"))) }
        assertEquals(1, albumsChangedCount)
        assertTrue(viewModel.uiState.value is PhotoDetailUiState.Removed)
    }

    @Test
    fun `removeFromAlbum surfaces an error and stays Loaded on failure`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        val albumApi = mockk<AlbumApi>()
        coEvery { albumApi.removePhotos(any(), any()) } throws RuntimeException("boom")
        val viewModel = PhotoDetailViewModel(photoApi, "1", albumId = "album-1")

        viewModel.removeFromAlbum(albumApi)

        val state = viewModel.uiState.value as PhotoDetailUiState.Loaded
        assertEquals("boom", state.actionError)
        assertFalse(state.isMutating)
    }

    @Test
    fun `removeFromAlbum is a no-op when the lightbox has no album context`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        val albumApi = mockk<AlbumApi>()
        val viewModel = PhotoDetailViewModel(photoApi, "1", ioDispatcher = mainDispatcherRule.testDispatcher)

        viewModel.removeFromAlbum(albumApi)

        coVerify(exactly = 0) { albumApi.removePhotos(any(), any()) }
        assertTrue(viewModel.uiState.value is PhotoDetailUiState.Loaded)
    }

    @Test
    fun `addToAlbum calls the api with this photo's id and notifies onAlbumsChanged`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        val albumApi = mockk<AlbumApi>()
        coEvery { albumApi.addPhotos("album-1", AlbumPhotoRequest(listOf("1"))) } returns
            AlbumDTO("album-1", "Trip", null, null, 1, "now", "now")
        val viewModel = PhotoDetailViewModel(photoApi, "1", ioDispatcher = mainDispatcherRule.testDispatcher)
        var albumsChangedCount = 0

        viewModel.addToAlbum(albumApi, "album-1", onAlbumsChanged = { albumsChangedCount++ })

        coVerify(exactly = 1) { albumApi.addPhotos("album-1", AlbumPhotoRequest(listOf("1"))) }
        assertEquals(1, albumsChangedCount)
        val state = viewModel.uiState.value as PhotoDetailUiState.Loaded
        assertFalse(state.isMutating)
        assertNull(state.actionError)
    }

    @Test
    fun `createAlbumAndAdd creates the album then adds this photo and notifies onAlbumsChanged`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        val albumApi = mockk<AlbumApi>()
        val created = AlbumDTO("new-album", "Trip", null, null, 0, "now", "now")
        coEvery { albumApi.create(any()) } returns created
        coEvery { albumApi.addPhotos("new-album", AlbumPhotoRequest(listOf("1"))) } returns created
        val viewModel = PhotoDetailViewModel(photoApi, "1", ioDispatcher = mainDispatcherRule.testDispatcher)
        var albumsChangedCount = 0

        viewModel.createAlbumAndAdd(albumApi, "Trip", onAlbumsChanged = { albumsChangedCount++ })

        coVerify(exactly = 1) { albumApi.addPhotos("new-album", AlbumPhotoRequest(listOf("1"))) }
        assertEquals(1, albumsChangedCount)
    }

    @Test
    fun `updateDate replaces the loaded photo with the api's response`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1", fullDateTime = "2026-01-01T12:00:00")
        val updated = testPhoto("1", fullDateTime = "2026-06-01T10:30:00")
        coEvery { photoApi.updateDate("1", any()) } returns updated
        val viewModel = PhotoDetailViewModel(photoApi, "1", ioDispatcher = mainDispatcherRule.testDispatcher)

        viewModel.updateDate(java.time.LocalDateTime.of(2026, 6, 1, 10, 30))

        val state = viewModel.uiState.value as PhotoDetailUiState.Loaded
        assertEquals("2026-06-01T10:30:00", state.photo.fullDateTime)
    }

    @Test
    fun `dismissActionError clears the error`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        coEvery { photoApi.moveToGarbage(any()) } throws RuntimeException("boom")
        val viewModel = PhotoDetailViewModel(photoApi, "1", ioDispatcher = mainDispatcherRule.testDispatcher)
        viewModel.moveToGarbage()

        viewModel.dismissActionError()

        val state = viewModel.uiState.value as PhotoDetailUiState.Loaded
        assertNull(state.actionError)
    }

    @Test
    fun `saveEditedPhoto with replace updates loaded state and deletes temp file`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        val updated = testPhoto("1").copy(title = "edited.jpg")
        coEvery { photoApi.editPhoto("1", any(), false) } returns updated

        val viewModel = PhotoDetailViewModel(photoApi, "1", ioDispatcher = mainDispatcherRule.testDispatcher)
        val tempFile = java.io.File.createTempFile("test_edit", ".jpg").apply { writeText("dummy content") }
        assertTrue(tempFile.exists())

        var callbackCalled = false
        viewModel.saveEditedPhoto(tempFile, saveAsNew = false) { result ->
            callbackCalled = true
            assertEquals("edited.jpg", result.title)
        }

        assertTrue(callbackCalled)
        val state = viewModel.uiState.value as PhotoDetailUiState.Loaded
        assertEquals("edited.jpg", state.photo.title)
        assertFalse(state.isMutating)
        assertFalse(tempFile.exists())
    }

    @Test
    fun `saveEditedPhoto with saveAsNew updates loaded state to new photo and deletes temp file`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        val newPhoto = testPhoto("new-2").copy(title = "new.jpg")
        coEvery { photoApi.editPhoto("1", any(), true) } returns newPhoto

        val viewModel = PhotoDetailViewModel(photoApi, "1", ioDispatcher = mainDispatcherRule.testDispatcher)
        val tempFile = java.io.File.createTempFile("test_edit", ".jpg").apply { writeText("dummy content") }
        assertTrue(tempFile.exists())

        var callbackPhoto: Photo? = null
        viewModel.saveEditedPhoto(tempFile, saveAsNew = true) { result ->
            callbackPhoto = result
        }

        assertEquals("new-2", callbackPhoto?.id)
        val state = viewModel.uiState.value as PhotoDetailUiState.Loaded
        assertEquals("new-2", state.photo.id)
        assertFalse(state.isMutating)
        assertFalse(tempFile.exists())
    }

    @Test
    fun `saveEditedPhoto failure sets actionError and deletes temp file`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        coEvery { photoApi.editPhoto("1", any(), any()) } throws RuntimeException("upload failed")

        val viewModel = PhotoDetailViewModel(photoApi, "1", ioDispatcher = mainDispatcherRule.testDispatcher)
        val tempFile = java.io.File.createTempFile("test_edit", ".jpg").apply { writeText("dummy content") }

        viewModel.saveEditedPhoto(tempFile, saveAsNew = false) { }

        val state = viewModel.uiState.value as PhotoDetailUiState.Loaded
        assertEquals("upload failed", state.actionError)
        assertFalse(state.isMutating)
        assertFalse(tempFile.exists())
    }

    @Test
    fun `saveEditedPhoto with contentResolver streams bytes and uploads`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        val updated = testPhoto("1").copy(title = "edited_resolver.jpg")
        coEvery { photoApi.editPhoto("1", any(), false) } returns updated

        val viewModel = PhotoDetailViewModel(photoApi, "1", ioDispatcher = mainDispatcherRule.testDispatcher)
        val contentResolver = mockk<android.content.ContentResolver>()
        val uri = mockk<android.net.Uri>()
        val stream = java.io.ByteArrayInputStream("image bytes".toByteArray())
        io.mockk.every { contentResolver.openInputStream(uri) } returns stream

        var callbackCalled = false
        viewModel.saveEditedPhoto(contentResolver, uri, saveAsNew = false) { result ->
            callbackCalled = true
            assertEquals("edited_resolver.jpg", result.title)
        }

        assertTrue(callbackCalled)
        val state = viewModel.uiState.value as PhotoDetailUiState.Loaded
        assertEquals("edited_resolver.jpg", state.photo.title)
        assertFalse(state.isMutating)
    }
}
