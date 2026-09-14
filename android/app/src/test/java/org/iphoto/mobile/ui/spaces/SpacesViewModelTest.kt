package org.iphoto.mobile.ui.spaces

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.iphoto.mobile.network.SpaceApi
import org.iphoto.mobile.testutil.MainDispatcherRule
import org.iphoto.mobile.testutil.testSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SpacesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `loads spaces on init`() = runTest {
        val spaceApi = mockk<SpaceApi>()
        coEvery { spaceApi.list() } returns listOf(testSpace("s1"), testSpace("s2"))

        val viewModel = SpacesViewModel(spaceApi)

        val state = viewModel.uiState.value
        assertEquals(listOf("s1", "s2"), state.spaces.map { it.id })
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `falls back to an error state when loading fails`() = runTest {
        val spaceApi = mockk<SpaceApi>()
        coEvery { spaceApi.list() } throws RuntimeException("boom")

        val viewModel = SpacesViewModel(spaceApi)

        val state = viewModel.uiState.value
        assertTrue(state.spaces.isEmpty())
        assertFalse(state.isLoading)
        assertEquals("boom", state.error)
    }

    @Test
    fun `a failure with no message falls back to a default error`() = runTest {
        val spaceApi = mockk<SpaceApi>()
        coEvery { spaceApi.list() } throws RuntimeException()

        val viewModel = SpacesViewModel(spaceApi)

        assertEquals("Failed to load spaces", viewModel.uiState.value.error)
    }

    @Test
    fun `load reloads and replaces the previous list`() = runTest {
        val spaceApi = mockk<SpaceApi>()
        coEvery { spaceApi.list() } returns listOf(testSpace("s1"))
        val viewModel = SpacesViewModel(spaceApi)
        coEvery { spaceApi.list() } returns listOf(testSpace("s2"), testSpace("s3"))

        viewModel.load()

        val state = viewModel.uiState.value
        assertEquals(listOf("s2", "s3"), state.spaces.map { it.id })
        coVerify(exactly = 2) { spaceApi.list() }
    }

    @Test
    fun `load clears a previous error once it succeeds`() = runTest {
        val spaceApi = mockk<SpaceApi>()
        coEvery { spaceApi.list() } throws RuntimeException("boom")
        val viewModel = SpacesViewModel(spaceApi)
        assertEquals("boom", viewModel.uiState.value.error)
        coEvery { spaceApi.list() } returns listOf(testSpace("s1"))

        viewModel.load()

        val state = viewModel.uiState.value
        assertNull(state.error)
        assertEquals(listOf("s1"), state.spaces.map { it.id })
    }

    // The Main space is flagged non-deletable server-side (deletionAllowed = false) - not filtered
    // here (SpacesScreen shows every space, unlike SyncViewModel's sync-target picker, which does
    // filter on this), but worth pinning that the flag survives the round trip unchanged, since
    // SpacesScreen's "delete" affordance for a given space is expected to read it directly off
    // this state.
    @Test
    fun `deletionAllowed is preserved as returned by the api`() = runTest {
        val spaceApi = mockk<SpaceApi>()
        coEvery { spaceApi.list() } returns listOf(
            testSpace("main", deletionAllowed = false),
            testSpace("s1", deletionAllowed = true)
        )

        val viewModel = SpacesViewModel(spaceApi)

        val state = viewModel.uiState.value
        assertEquals(false, state.spaces.first { it.id == "main" }.deletionAllowed)
        assertEquals(true, state.spaces.first { it.id == "s1" }.deletionAllowed)
    }
}
