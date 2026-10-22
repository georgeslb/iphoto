package org.iphoto.mobile.ui.common

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
    fun `loadMore appends the next page, advances the page counter, and updates endReached`() = runTest {
        val firstPage = listOf(testPhoto("1"), testPhoto("2"))
        val secondPage = listOf(testPhoto("3"))
        val requestedPages = mutableListOf<Int>()
        val loadPage: suspend (Int, Int) -> Pair<List<Photo>, Boolean> = { page, _ ->
            requestedPages.add(page)
            if (page == 0) firstPage to false else secondPage to true
        }
        val viewModel = PagedPhotoGridViewModel(loadPage)

        viewModel.loadMore()

        assertEquals(listOf(0, 1), requestedPages)
        val state = viewModel.uiState.value
        assertEquals(firstPage + secondPage, state.photos)
        assertTrue(state.endReached)
        assertFalse(state.isLoadingMore)
        assertNull(state.error)
    }

    @Test
    fun `loadMore is a no-op once endReached`() = runTest {
        var callCount = 0
        val loadPage: suspend (Int, Int) -> Pair<List<Photo>, Boolean> = { _, _ ->
            callCount++
            listOf(testPhoto("1")) to true // already the last page from the first load
        }
        val viewModel = PagedPhotoGridViewModel(loadPage)
        assertEquals(1, callCount)

        viewModel.loadMore()

        assertEquals(1, callCount)
    }

    @Test
    fun `loadMore is a no-op while the first page is still loading`() = runTest {
        val deferred = CompletableDeferred<Pair<List<Photo>, Boolean>>()
        var callCount = 0
        val loadPage: suspend (Int, Int) -> Pair<List<Photo>, Boolean> = { _, _ ->
            callCount++
            deferred.await()
        }
        val viewModel = PagedPhotoGridViewModel(loadPage)
        assertTrue(viewModel.uiState.value.isLoading)

        viewModel.loadMore()

        // The isLoading guard should have stopped loadMore from calling loadPage a second time.
        assertEquals(1, callCount)
        deferred.complete(listOf(testPhoto("1")) to true)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `loadMore ignores a second call while one is already in flight`() = runTest {
        val firstPage = listOf(testPhoto("1"))
        var callCount = 0
        val deferred = CompletableDeferred<Pair<List<Photo>, Boolean>>()
        val loadPage: suspend (Int, Int) -> Pair<List<Photo>, Boolean> = { page, _ ->
            callCount++
            if (page == 0) firstPage to false else deferred.await()
        }
        val viewModel = PagedPhotoGridViewModel(loadPage)
        assertEquals(1, callCount)

        viewModel.loadMore() // page 1, suspends on the deferred
        assertEquals(2, callCount)
        assertTrue(viewModel.uiState.value.isLoadingMore)

        viewModel.loadMore() // isLoadingMore guard should ignore this one

        assertEquals(2, callCount)
        deferred.complete(listOf(testPhoto("2")) to true)
        assertEquals(firstPage + listOf(testPhoto("2")), viewModel.uiState.value.photos)
    }

    @Test
    fun `a failed loadMore preserves the existing photos and endReached, and doesn't advance the page`() = runTest {
        val firstPage = listOf(testPhoto("1"))
        val requestedPages = mutableListOf<Int>()
        val loadPage: suspend (Int, Int) -> Pair<List<Photo>, Boolean> = { page, _ ->
            requestedPages.add(page)
            if (page == 0) firstPage to false else error("network down")
        }
        val viewModel = PagedPhotoGridViewModel(loadPage)

        viewModel.loadMore()

        assertEquals(listOf(0, 1), requestedPages)
        val state = viewModel.uiState.value
        assertEquals("network down", state.error)
        assertFalse(state.isLoadingMore)
        assertEquals(firstPage, state.photos)
        assertFalse(state.endReached)
    }

    @Test
    fun `retrying loadMore after a failure re-requests the same page, not the next one`() = runTest {
        val firstPage = listOf(testPhoto("1"))
        val requestedPages = mutableListOf<Int>()
        var shouldFail = true
        val loadPage: suspend (Int, Int) -> Pair<List<Photo>, Boolean> = { page, _ ->
            requestedPages.add(page)
            when {
                page == 0 -> firstPage to false
                shouldFail -> error("network down")
                else -> listOf(testPhoto("2")) to true
            }
        }
        val viewModel = PagedPhotoGridViewModel(loadPage)
        viewModel.loadMore() // fails, requests page 1
        shouldFail = false

        viewModel.loadMore() // retry

        assertEquals(listOf(0, 1, 1), requestedPages)
        val state = viewModel.uiState.value
        assertEquals(firstPage + listOf(testPhoto("2")), state.photos)
        assertTrue(state.endReached)
        // A successful retry clears the stale error from the previous failed attempt - loadMore's
        // success branch didn't used to reset this field at all (unlike loadFirstPage, which
        // always builds a fresh PagedPhotosUiState()), so a retry after a failure would silently
        // leave the old error message sitting in state forever even though the retry succeeded.
        assertNull(state.error)
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
    fun `canMoveToMain defaults to false`() {
        val viewModel = PagedPhotoGridViewModel({ _, _ -> emptyList<Photo>() to true })

        assertFalse(viewModel.canMoveToMain)
    }

    @Test
    fun `moveSelectionToMain moves the selection`() = runTest {
        val viewModel = PagedPhotoGridViewModel(
            { _, _ -> listOf(testPhoto("1"), testPhoto("2")) to true },
            canMoveToMain = true
        )
        viewModel.toggleSelection("1")
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.moveToMain(listOf("1")) } returns Unit

        assertTrue(viewModel.canMoveToMain)
        viewModel.moveSelectionToMain(photoApi)

        coVerify(exactly = 1) { photoApi.moveToMain(listOf("1")) }
        val state = viewModel.uiState.value
        assertTrue(state.selectedIds.isEmpty())
        assertFalse(state.isMutating)
        assertNull(state.actionError)
    }

    @Test
    fun `moveSelectionToMain surfaces an error and stops mutating on failure`() = runTest {
        val viewModel = PagedPhotoGridViewModel(
            { _, _ -> listOf(testPhoto("1")) to true },
            canMoveToMain = true
        )
        viewModel.toggleSelection("1")
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.moveToMain(any()) } throws RuntimeException("network down")

        viewModel.moveSelectionToMain(photoApi)

        val state = viewModel.uiState.value
        assertEquals("network down", state.actionError)
        assertFalse(state.isMutating)
        assertEquals(setOf("1"), state.selectedIds)
    }

    @Test
    fun `moveSelectionToMain is a no-op with nothing selected`() = runTest {
        val viewModel = PagedPhotoGridViewModel(
            { _, _ -> listOf(testPhoto("1")) to true },
            canMoveToMain = true
        )
        val photoApi = mockk<PhotoApi>()

        viewModel.moveSelectionToMain(photoApi)

        coVerify(exactly = 0) { photoApi.moveToMain(any()) }
    }

    @Test
    fun `addSelectionToAlbum adds photos without reloading the page and notifies onAlbumsChanged`() = runTest {
        val photos = listOf(testPhoto("1"), testPhoto("2"))
        val viewModel = viewModelWith(photos)
        viewModel.toggleSelection("1")
        viewModel.toggleSelection("2")
        val albumApi = mockk<AlbumApi>()
        coEvery { albumApi.addPhotos("album-1", AlbumPhotoRequest(listOf("1", "2"))) } returns
            AlbumDTO("album-1", "Trip", null, null, 2, "now", "now")
        var albumsChangedCount = 0

        viewModel.addSelectionToAlbum(albumApi, "album-1", onAlbumsChanged = { albumsChangedCount++ })

        coVerify(exactly = 1) { albumApi.addPhotos("album-1", AlbumPhotoRequest(listOf("1", "2"))) }
        assertEquals(1, albumsChangedCount)
        val state = viewModel.uiState.value
        assertTrue(state.selectedIds.isEmpty())
        // Adding to an album is a reference, not a move - the photos stay in this view.
        assertEquals(photos, state.photos)
    }

    @Test
    fun `createAlbumAndAddSelection creates the album then adds the selection to it and notifies onAlbumsChanged`() = runTest {
        val viewModel = viewModelWith(listOf(testPhoto("1")))
        viewModel.toggleSelection("1")
        val albumApi = mockk<AlbumApi>()
        val created = AlbumDTO("new-album", "Trip", null, null, 0, "now", "now")
        coEvery { albumApi.create(any()) } returns created
        coEvery { albumApi.addPhotos("new-album", AlbumPhotoRequest(listOf("1"))) } returns created
        var albumsChangedCount = 0

        viewModel.createAlbumAndAddSelection(albumApi, "Trip", onAlbumsChanged = { albumsChangedCount++ })

        coVerify(exactly = 1) { albumApi.addPhotos("new-album", AlbumPhotoRequest(listOf("1"))) }
        assertEquals(1, albumsChangedCount)
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
    fun `removeSelectionFromAlbum removes selected photos when inside an album and notifies onAlbumsChanged`() = runTest {
        val viewModel = viewModelWith(listOf(testPhoto("1"), testPhoto("2")), albumId = "album-1")
        viewModel.toggleSelection("1")
        val albumApi = mockk<AlbumApi>()
        coEvery { albumApi.removePhotos("album-1", AlbumPhotoRequest(listOf("1"))) } returns
            AlbumDTO("album-1", "Trip", null, null, 1, "now", "now")
        var albumsChangedCount = 0

        viewModel.removeSelectionFromAlbum(albumApi, onAlbumsChanged = { albumsChangedCount++ })

        coVerify(exactly = 1) { albumApi.removePhotos("album-1", AlbumPhotoRequest(listOf("1"))) }
        assertEquals(1, albumsChangedCount)
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

    @Test
    fun `reorderPhotos calls updateDate for each update, reloads first page, and retains active selection`() = runTest {
        val initialPhotos = listOf(testPhoto("1"), testPhoto("2"))
        val reloadedPhotos = listOf(testPhoto("2"), testPhoto("1"))
        var loadCount = 0
        val loadPage: suspend (Int, Int) -> Pair<List<Photo>, Boolean> = { _, _ ->
            loadCount++
            if (loadCount == 1) initialPhotos to true else reloadedPhotos to true
        }
        val viewModel = PagedPhotoGridViewModel(loadPage)
        viewModel.toggleSelection("1")
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.updateDate(any(), any()) } returns testPhoto("x")

        val updates = listOf(
            ReorderUpdate("1", "2024-03-15T14:00:06.000"),
            ReorderUpdate("2", "2024-03-15T14:00:05.000")
        )
        viewModel.reorderPhotos(photoApi, updates)

        coVerify(exactly = 1) { photoApi.updateDate("1", UpdatePhotoDateRequest("2024-03-15T14:00:06.000")) }
        coVerify(exactly = 1) { photoApi.updateDate("2", UpdatePhotoDateRequest("2024-03-15T14:00:05.000")) }
        val state = viewModel.uiState.value
        assertEquals(reloadedPhotos, state.photos)
        assertEquals(setOf("1"), state.selectedIds)
        assertFalse(state.isMutating)
        assertNull(state.actionError)
    }

    @Test
    fun `reorderPhotos surfaces action error on failure`() = runTest {
        val viewModel = viewModelWith(listOf(testPhoto("1")))
        val photoApi = mockk<PhotoApi>()
        coEvery { photoApi.updateDate(any(), any()) } throws RuntimeException("update failed")

        val updates = listOf(ReorderUpdate("1", "2024-03-15T14:00:06.000"))
        viewModel.reorderPhotos(photoApi, updates)

        val state = viewModel.uiState.value
        assertEquals("update failed", state.actionError)
        assertFalse(state.isMutating)
    }

    @Test
    fun `reorderPhotos is a no-op when updates list is empty`() = runTest {
        val viewModel = viewModelWith(listOf(testPhoto("1")))
        val photoApi = mockk<PhotoApi>()

        viewModel.reorderPhotos(photoApi, emptyList())

        coVerify(exactly = 0) { photoApi.updateDate(any(), any()) }
        assertFalse(viewModel.uiState.value.isMutating)
    }
}
