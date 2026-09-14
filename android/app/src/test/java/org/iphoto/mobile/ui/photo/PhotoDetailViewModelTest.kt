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

        val viewModel = PhotoDetailViewModel(photoApi, "1")

        val state = viewModel.uiState.value as PhotoDetailUiState.Loaded
        assertEquals("1", state.photo.id)
        assertFalse(state.isMutating)
    }

    @Test
    fun `falls back to Failed when loading throws`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } throws RuntimeException("not found")

        val viewModel = PhotoDetailViewModel(photoApi, "1")

        val state = viewModel.uiState.value
        assertTrue(state is PhotoDetailUiState.Failed)
        assertEquals("not found", (state as PhotoDetailUiState.Failed).message)
    }

    @Test
    fun `moveToGarbage transitions to Removed on success`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        coEvery { photoApi.moveToGarbage(listOf("1")) } returns Unit
        val viewModel = PhotoDetailViewModel(photoApi, "1")

        viewModel.moveToGarbage()

        assertTrue(viewModel.uiState.value is PhotoDetailUiState.Removed)
    }

    @Test
    fun `moveToGarbage surfaces the error and stays Loaded on failure`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        coEvery { photoApi.moveToGarbage(any()) } throws RuntimeException("boom")
        val viewModel = PhotoDetailViewModel(photoApi, "1")

        viewModel.moveToGarbage()

        val state = viewModel.uiState.value as PhotoDetailUiState.Loaded
        assertEquals("boom", state.actionError)
        assertFalse(state.isMutating)
    }

    @Test
    fun `moveToGarbage invokes onPhotoMovedToGarbage with the loaded photo after the api call`() = runTest {
        val photoApi = mockk<PhotoApi>()
        val photo = testPhoto("1")
        coEvery { photoApi.get("1") } returns photo
        coEvery { photoApi.moveToGarbage(listOf("1")) } returns Unit
        var invokedWith: Photo? = null
        val viewModel = PhotoDetailViewModel(photoApi, "1", onPhotoMovedToGarbage = { invokedWith = it })

        viewModel.moveToGarbage()

        assertEquals(photo, invokedWith)
        assertTrue(viewModel.uiState.value is PhotoDetailUiState.Removed)
    }

    @Test
    fun `moveToGarbage still transitions to Removed when onPhotoMovedToGarbage throws`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        coEvery { photoApi.moveToGarbage(listOf("1")) } returns Unit
        val viewModel = PhotoDetailViewModel(
            photoApi,
            "1",
            onPhotoMovedToGarbage = { throw RuntimeException("local trash unavailable") }
        )

        viewModel.moveToGarbage()

        assertTrue(viewModel.uiState.value is PhotoDetailUiState.Removed)
    }

    @Test
    fun `canExcludeFromSync is true only when onPhotoExcludedFromSync is set`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")

        val without = PhotoDetailViewModel(photoApi, "1")
        val with = PhotoDetailViewModel(photoApi, "1", onPhotoExcludedFromSync = {})

        assertFalse(without.canExcludeFromSync)
        assertTrue(with.canExcludeFromSync)
    }

    @Test
    fun `excludeFromSync moves to garbage, invokes the callback, and transitions to Removed`() = runTest {
        val photoApi = mockk<PhotoApi>()
        val photo = testPhoto("1")
        coEvery { photoApi.get("1") } returns photo
        coEvery { photoApi.moveToGarbage(listOf("1")) } returns Unit
        var invokedWith: Photo? = null
        val viewModel = PhotoDetailViewModel(photoApi, "1", onPhotoExcludedFromSync = { invokedWith = it })

        viewModel.excludeFromSync(photoApi)

        assertEquals(photo, invokedWith)
        assertTrue(viewModel.uiState.value is PhotoDetailUiState.Removed)
    }

    @Test
    fun `excludeFromSync surfaces the error and stays Loaded on failure`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        coEvery { photoApi.moveToGarbage(any()) } throws RuntimeException("boom")
        val viewModel = PhotoDetailViewModel(photoApi, "1", onPhotoExcludedFromSync = {})

        viewModel.excludeFromSync(photoApi)

        val state = viewModel.uiState.value as PhotoDetailUiState.Loaded
        assertEquals("boom", state.actionError)
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
    fun `moveToMain with alsoRemoveFromDevice invokes onPhotoMovedToGarbage`() = runTest {
        val photoApi = mockk<PhotoApi>()
        val photo = testPhoto("1")
        coEvery { photoApi.get("1") } returns photo
        coEvery { photoApi.moveToMain(listOf("1")) } returns Unit
        var invokedWith: Photo? = null
        val viewModel = PhotoDetailViewModel(
            photoApi,
            "1",
            onPhotoMovedToGarbage = { invokedWith = it },
            canMoveToMain = true
        )

        viewModel.moveToMain(photoApi, alsoRemoveFromDevice = true)

        assertEquals(photo, invokedWith)
        assertTrue(viewModel.uiState.value is PhotoDetailUiState.Removed)
    }

    @Test
    fun `moveToMain without alsoRemoveFromDevice does not invoke onPhotoMovedToGarbage`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        coEvery { photoApi.moveToMain(listOf("1")) } returns Unit
        var invoked = false
        val viewModel = PhotoDetailViewModel(
            photoApi,
            "1",
            onPhotoMovedToGarbage = { invoked = true },
            canMoveToMain = true
        )

        viewModel.moveToMain(photoApi, alsoRemoveFromDevice = false)

        assertFalse(invoked)
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
    fun `removeFromAlbum calls the api with this photo's id and transitions to Removed`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        val albumApi = mockk<AlbumApi>()
        coEvery { albumApi.removePhotos("album-1", AlbumPhotoRequest(listOf("1"))) } returns
            AlbumDTO("album-1", "Trip", null, null, 0, "now", "now")
        val viewModel = PhotoDetailViewModel(photoApi, "1", albumId = "album-1")

        viewModel.removeFromAlbum(albumApi)

        coVerify(exactly = 1) { albumApi.removePhotos("album-1", AlbumPhotoRequest(listOf("1"))) }
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
        val viewModel = PhotoDetailViewModel(photoApi, "1")

        viewModel.removeFromAlbum(albumApi)

        coVerify(exactly = 0) { albumApi.removePhotos(any(), any()) }
        assertTrue(viewModel.uiState.value is PhotoDetailUiState.Loaded)
    }

    @Test
    fun `addToAlbum calls the api with this photo's id`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        val albumApi = mockk<AlbumApi>()
        coEvery { albumApi.addPhotos("album-1", AlbumPhotoRequest(listOf("1"))) } returns
            AlbumDTO("album-1", "Trip", null, null, 1, "now", "now")
        val viewModel = PhotoDetailViewModel(photoApi, "1")

        viewModel.addToAlbum(albumApi, "album-1")

        coVerify(exactly = 1) { albumApi.addPhotos("album-1", AlbumPhotoRequest(listOf("1"))) }
        val state = viewModel.uiState.value as PhotoDetailUiState.Loaded
        assertFalse(state.isMutating)
        assertNull(state.actionError)
    }

    @Test
    fun `createAlbumAndAdd creates the album then adds this photo`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        val albumApi = mockk<AlbumApi>()
        val created = AlbumDTO("new-album", "Trip", null, null, 0, "now", "now")
        coEvery { albumApi.create(any()) } returns created
        coEvery { albumApi.addPhotos("new-album", AlbumPhotoRequest(listOf("1"))) } returns created
        val viewModel = PhotoDetailViewModel(photoApi, "1")

        viewModel.createAlbumAndAdd(albumApi, "Trip")

        coVerify(exactly = 1) { albumApi.addPhotos("new-album", AlbumPhotoRequest(listOf("1"))) }
    }

    @Test
    fun `updateDate replaces the loaded photo with the api's response`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1", fullDateTime = "2026-01-01T12:00:00")
        val updated = testPhoto("1", fullDateTime = "2026-06-01T10:30:00")
        coEvery { photoApi.updateDate("1", any()) } returns updated
        val viewModel = PhotoDetailViewModel(photoApi, "1")

        viewModel.updateDate(java.time.LocalDateTime.of(2026, 6, 1, 10, 30))

        val state = viewModel.uiState.value as PhotoDetailUiState.Loaded
        assertEquals("2026-06-01T10:30:00", state.photo.fullDateTime)
    }

    @Test
    fun `dismissActionError clears the error`() = runTest {
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.get("1") } returns testPhoto("1")
        coEvery { photoApi.moveToGarbage(any()) } throws RuntimeException("boom")
        val viewModel = PhotoDetailViewModel(photoApi, "1")
        viewModel.moveToGarbage()

        viewModel.dismissActionError()

        val state = viewModel.uiState.value as PhotoDetailUiState.Loaded
        assertNull(state.actionError)
    }
}
