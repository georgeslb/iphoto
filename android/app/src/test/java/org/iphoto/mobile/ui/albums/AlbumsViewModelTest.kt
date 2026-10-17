package org.iphoto.mobile.ui.albums

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.iphoto.mobile.network.AlbumApi
import org.iphoto.mobile.testutil.MainDispatcherRule
import org.iphoto.mobile.testutil.testAlbum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AlbumsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `loads albums on init`() = runTest {
        val albumApi = mockk<AlbumApi>()
        coEvery { albumApi.list() } returns listOf(testAlbum("a1"), testAlbum("a2"))

        val viewModel = AlbumsViewModel(albumApi)

        val state = viewModel.uiState.value
        assertEquals(listOf("a1", "a2"), state.albums.map { it.id })
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `falls back to an error state when loading fails`() = runTest {
        val albumApi = mockk<AlbumApi>()
        coEvery { albumApi.list() } throws RuntimeException("boom")

        val viewModel = AlbumsViewModel(albumApi)

        val state = viewModel.uiState.value
        assertTrue(state.albums.isEmpty())
        assertFalse(state.isLoading)
        assertEquals("boom", state.error)
    }

    @Test
    fun `a failure with no message falls back to a default error`() = runTest {
        val albumApi = mockk<AlbumApi>()
        coEvery { albumApi.list() } throws RuntimeException()

        val viewModel = AlbumsViewModel(albumApi)

        assertEquals("Failed to load albums", viewModel.uiState.value.error)
    }

    @Test
    fun `load reloads and replaces the previous list`() = runTest {
        val albumApi = mockk<AlbumApi>()
        coEvery { albumApi.list() } returns listOf(testAlbum("a1"))
        val viewModel = AlbumsViewModel(albumApi)
        coEvery { albumApi.list() } returns listOf(testAlbum("a2"), testAlbum("a3"))

        viewModel.load()

        val state = viewModel.uiState.value
        assertEquals(listOf("a2", "a3"), state.albums.map { it.id })
        coVerify(exactly = 2) { albumApi.list() }
    }

    @Test
    fun `load clears a previous error once it succeeds`() = runTest {
        val albumApi = mockk<AlbumApi>()
        coEvery { albumApi.list() } throws RuntimeException("boom")
        val viewModel = AlbumsViewModel(albumApi)
        assertEquals("boom", viewModel.uiState.value.error)
        coEvery { albumApi.list() } returns listOf(testAlbum("a1"))

        viewModel.load()

        val state = viewModel.uiState.value
        assertNull(state.error)
        assertEquals(listOf("a1"), state.albums.map { it.id })
    }

    @Test
    fun `loads and sorts albums by creation date descending`() = runTest {
        val albumApi = mockk<AlbumApi>()
        coEvery { albumApi.list() } returns listOf(
            testAlbum("oldest", createdAt = "2026-01-01T10:00:00"),
            testAlbum("newest", createdAt = "2026-03-15T18:30:00"),
            testAlbum("middle", createdAt = "2026-02-10T09:15:00")
        )

        val viewModel = AlbumsViewModel(albumApi)

        assertEquals(
            listOf("newest", "middle", "oldest"),
            viewModel.uiState.value.albums.map { it.id }
        )
    }
}
