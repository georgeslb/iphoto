package org.iphoto.mobile.auth

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.iphoto.mobile.model.UserDto
import org.iphoto.mobile.testutil.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AuthViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val user = UserDto(id = "u1", username = "alice", role = "USER")

    @Test
    fun `starts LoggedOut when there is no stored session`() {
        val repository = mockk<AuthRepository>()
        every { repository.isLoggedIn } returns false

        val viewModel = AuthViewModel(repository)

        assertTrue(viewModel.uiState.value is AuthUiState.LoggedOut)
    }

    @Test
    fun `resumes into LoggedIn when a stored session is still valid`() = runTest {
        val repository = mockk<AuthRepository>()
        every { repository.isLoggedIn } returns true
        coEvery { repository.currentUser() } returns user

        val viewModel = AuthViewModel(repository)

        val state = viewModel.uiState.value as AuthUiState.LoggedIn
        assertEquals(user, state.user)
    }

    @Test
    fun `falls back to LoggedOut when the stored token is no longer valid`() = runTest {
        val repository = mockk<AuthRepository>()
        every { repository.isLoggedIn } returns true
        coEvery { repository.currentUser() } throws RuntimeException("401")

        val viewModel = AuthViewModel(repository)

        assertTrue(viewModel.uiState.value is AuthUiState.LoggedOut)
    }

    @Test
    fun `login transitions to LoggedIn on success`() = runTest {
        val repository = mockk<AuthRepository>()
        every { repository.isLoggedIn } returns false
        coEvery { repository.login("http://host:8080", "alice", "pw") } returns user
        val viewModel = AuthViewModel(repository)

        viewModel.login("http://host:8080", "alice", "pw")

        val state = viewModel.uiState.value as AuthUiState.LoggedIn
        assertEquals(user, state.user)
    }

    @Test
    fun `login surfaces the failure message and stops loading`() = runTest {
        val repository = mockk<AuthRepository>()
        every { repository.isLoggedIn } returns false
        coEvery { repository.login(any(), any(), any()) } throws RuntimeException("Invalid credentials")
        val viewModel = AuthViewModel(repository)

        viewModel.login("http://host:8080", "alice", "wrong")

        val state = viewModel.uiState.value as AuthUiState.LoggedOut
        assertEquals("Invalid credentials", state.error)
        assertEquals(false, state.isLoading)
    }

    @Test
    fun `logout calls the repository and returns to LoggedOut`() = runTest {
        val repository = mockk<AuthRepository>()
        every { repository.isLoggedIn } returns true
        coEvery { repository.currentUser() } returns user
        coEvery { repository.logout() } returns Unit
        val viewModel = AuthViewModel(repository)

        viewModel.logout()

        coVerify(exactly = 1) { repository.logout() }
        assertTrue(viewModel.uiState.value is AuthUiState.LoggedOut)
    }
}
