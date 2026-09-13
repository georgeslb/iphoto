package org.iphoto.mobile.ui.common

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.iphoto.mobile.model.AlbumDTO
import org.iphoto.mobile.model.AlbumPhotoRequest
import org.iphoto.mobile.model.Photo
import org.iphoto.mobile.model.UpdatePhotoDateRequest
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

class PagedPhotoGridViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModelWith(
        photos: List<Photo>,
        endReached: Boolean = true,
        albumId: String? = null
    ): PagedPhotoGridViewModel {
        val loadPage: suspend (Int, Int) -> Pair<List<Photo>, Boolean> = { _, _ -> photos to endReached }
        return PagedPhotoGridViewModel(loadPage, albumId)
    }

    @Test
    fun `loadFirstPage populates photos and endReached`() = runTest {
        val photos = listOf(testPhoto("1"), testPhoto("2"))
        val viewModel = viewModelWith(photos, endReached = true)

        val state = viewModel.uiState.value
        assertEquals(photos, state.photos)
        assertFalse(state.isLoading)
        assertTrue(state.endReached)
        assertNull(state.error)
    }

    @Test
    fun `loadFirstPage surfaces the failure message on error`() = runTest {
        val loadPage: suspend (Int, Int) -> Pair<List<Photo>, Boolean> = { _, _ -> error("boom") }
        val viewModel = PagedPhotoGridViewModel(loadPage)

        val state = viewModel.uiState.value
        assertEquals("boom", state.error)
        assertFalse(state.isLoading)
        assertTrue(state.photos.isEmpty())
    }

    @Test
    fun `toggleSelection adds then removes a photo id`() {
        val viewModel = viewModelWith(listOf(testPhoto("1")))

        viewModel.toggleSelection("1")
        assertEquals(setOf("1"), viewModel.uiState.value.selectedIds)
        assertTrue(viewModel.uiState.value.isSelectionMode)

        viewModel.toggleSelection("1")
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
        assertFalse(viewModel.uiState.value.isSelectionMode)
    }

    @Test
    fun `toggleDaySelection selects the whole day when not all selected`() {
        val viewModel = viewModelWith(listOf(testPhoto("1"), testPhoto("2"), testPhoto("3")))
        viewModel.toggleSelection("2") // day is partially selected already

        viewModel.toggleDaySelection(listOf("1", "2", "3"))

        assertEquals(setOf("1", "2", "3"), viewModel.uiState.value.selectedIds)
    }

    @Test
    fun `toggleDaySelection deselects the whole day when already all selected`() {
        val viewModel = viewModelWith(listOf(testPhoto("1"), testPhoto("2")))
        viewModel.toggleDaySelection(listOf("1", "2"))
        assertEquals(setOf("1", "2"), viewModel.uiState.value.selectedIds)

        viewModel.toggleDaySelection(listOf("1", "2"))

        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun `clearSelection empties the selection`() {
        val viewModel = viewModelWith(listOf(testPhoto("1")))
        viewModel.toggleSelection("1")

        viewModel.clearSelection()

        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun `moveSelectionToGarbage calls the api with selected ids then reloads and clears selection`() = runTest {
        val viewModel = viewModelWith(listOf(testPhoto("1"), testPhoto("2")))
        viewModel.toggleSelection("1")
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.moveToGarbage(listOf("1")) } returns Unit

        viewModel.moveSelectionToGarbage(photoApi)

        coVerify(exactly = 1) { photoApi.moveToGarbage(listOf("1")) }
        val state = viewModel.uiState.value
        assertTrue(state.selectedIds.isEmpty())
        assertFalse(state.isMutating)
        assertNull(state.actionError)
    }

    @Test
    fun `moveSelectionToGarbage surfaces an error and stops mutating on failure`() = runTest {
        val viewModel = viewModelWith(listOf(testPhoto("1")))
        viewModel.toggleSelection("1")
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.moveToGarbage(any()) } throws RuntimeException("network down")

        viewModel.moveSelectionToGarbage(photoApi)

        val state = viewModel.uiState.value
        assertEquals("network down", state.actionError)
        assertFalse(state.isMutating)
        // Selection is preserved so the user can retry without re-picking photos.
        assertEquals(setOf("1"), state.selectedIds)
    }

    @Test
    fun `moveSelectionToGarbage is a no-op with nothing selected`() = runTest {
        val viewModel = viewModelWith(listOf(testPhoto("1")))
        val photoApi = mockk<PhotoApi>()

        viewModel.moveSelectionToGarbage(photoApi)

        coVerify(exactly = 0) { photoApi.moveToGarbage(any()) }
    }

    @Test
    fun `addSelectionToAlbum adds photos without reloading the page`() = runTest {
        val photos = listOf(testPhoto("1"), testPhoto("2"))
        val viewModel = viewModelWith(photos)
        viewModel.toggleSelection("1")
        viewModel.toggleSelection("2")
        val albumApi = mockk<AlbumApi>()
        coEvery { albumApi.addPhotos("album-1", AlbumPhotoRequest(listOf("1", "2"))) } returns
            AlbumDTO("album-1", "Trip", null, null, 2, "now", "now")

        viewModel.addSelectionToAlbum(albumApi, "album-1")

        coVerify(exactly = 1) { albumApi.addPhotos("album-1", AlbumPhotoRequest(listOf("1", "2"))) }
        val state = viewModel.uiState.value
        assertTrue(state.selectedIds.isEmpty())
        // Adding to an album is a reference, not a move - the photos stay in this view.
        assertEquals(photos, state.photos)
    }

    @Test
    fun `createAlbumAndAddSelection creates the album then adds the selection to it`() = runTest {
        val viewModel = viewModelWith(listOf(testPhoto("1")))
        viewModel.toggleSelection("1")
        val albumApi = mockk<AlbumApi>()
        val created = AlbumDTO("new-album", "Trip", null, null, 0, "now", "now")
        coEvery { albumApi.create(any()) } returns created
        coEvery { albumApi.addPhotos("new-album", AlbumPhotoRequest(listOf("1"))) } returns created

        viewModel.createAlbumAndAddSelection(albumApi, "Trip")

        coVerify(exactly = 1) { albumApi.addPhotos("new-album", AlbumPhotoRequest(listOf("1"))) }
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun `updateSelectionDate calls the api once per selected photo then reloads`() = runTest {
        val viewModel = viewModelWith(listOf(testPhoto("1"), testPhoto("2")))
        viewModel.toggleSelection("1")
        viewModel.toggleSelection("2")
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.updateDate(any(), any()) } returns testPhoto("1")
        val newDate = java.time.LocalDateTime.of(2026, 6, 1, 10, 30)

        viewModel.updateSelectionDate(photoApi, newDate)

        coVerify(exactly = 1) { photoApi.updateDate("1", UpdatePhotoDateRequest("2026-06-01T10:30:00")) }
        coVerify(exactly = 1) { photoApi.updateDate("2", UpdatePhotoDateRequest("2026-06-01T10:30:00")) }
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun `removeSelectionFromAlbum is a no-op outside an album`() = runTest {
        val viewModel = viewModelWith(listOf(testPhoto("1")), albumId = null)
        viewModel.toggleSelection("1")
        val albumApi = mockk<AlbumApi>()

        viewModel.removeSelectionFromAlbum(albumApi)

        coVerify(exactly = 0) { albumApi.removePhotos(any(), any()) }
    }

    @Test
    fun `removeSelectionFromAlbum removes selected photos when inside an album`() = runTest {
        val viewModel = viewModelWith(listOf(testPhoto("1"), testPhoto("2")), albumId = "album-1")
        viewModel.toggleSelection("1")
        val albumApi = mockk<AlbumApi>()
        coEvery { albumApi.removePhotos("album-1", AlbumPhotoRequest(listOf("1"))) } returns
            AlbumDTO("album-1", "Trip", null, null, 1, "now", "now")

        viewModel.removeSelectionFromAlbum(albumApi)

        coVerify(exactly = 1) { albumApi.removePhotos("album-1", AlbumPhotoRequest(listOf("1"))) }
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun `dismissActionError clears the error`() = runTest {
        val viewModel = viewModelWith(listOf(testPhoto("1")))
        viewModel.toggleSelection("1")
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.moveToGarbage(any()) } throws RuntimeException("boom")
        viewModel.moveSelectionToGarbage(photoApi)
        assertEquals("boom", viewModel.uiState.value.actionError)

        viewModel.dismissActionError()

        assertNull(viewModel.uiState.value.actionError)
    }
}
