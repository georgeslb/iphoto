package org.iphoto.mobile.data

import org.iphoto.mobile.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression safety net for the pending androidx.security:security-crypto -> raw Android Keystore
 * migration (see android/TODO.md) - written against TokenStore's [FakeSharedPreferences]-backed
 * constructor precisely so it only exercises the get/set/clear *contract* below, not
 * EncryptedSharedPreferences/MasterKey internals. These tests should keep passing unchanged once
 * TokenStore's production constructor swaps what backs `prefs`, which is exactly what proves the
 * migration preserved behavior.
 */
class TokenStoreTest {

    @Test
    fun `starts logged out with nothing stored`() {
        val store = TokenStore(FakeSharedPreferences())

        assertFalse(store.isLoggedIn)
        assertNull(store.token)
        assertNull(store.serverUrl)
        assertNull(store.deviceTokenId)
    }

    @Test
    fun `persists serverUrl, token and deviceTokenId`() {
        val store = TokenStore(FakeSharedPreferences())

        store.serverUrl = "http://host:8080"
        store.token = "abc123"
        store.deviceTokenId = "device-1"

        assertEquals("http://host:8080", store.serverUrl)
        assertEquals("abc123", store.token)
        assertEquals("device-1", store.deviceTokenId)
        assertTrue(store.isLoggedIn)
    }

    @Test
    fun `isLoggedIn is false for a blank token`() {
        val store = TokenStore(FakeSharedPreferences())

        store.token = ""

        assertFalse(store.isLoggedIn)
    }

    @Test
    fun `isLoggedIn is false after the token is cleared back to null`() {
        val store = TokenStore(FakeSharedPreferences())
        store.token = "abc123"

        store.token = null

        assertFalse(store.isLoggedIn)
    }

    @Test
    fun `clearSession removes the token and device id but keeps the server url`() {
        val store = TokenStore(FakeSharedPreferences())
        store.serverUrl = "http://host:8080"
        store.token = "abc123"
        store.deviceTokenId = "device-1"

        store.clearSession()

        assertNull(store.token)
        assertNull(store.deviceTokenId)
        assertEquals("http://host:8080", store.serverUrl)
        assertFalse(store.isLoggedIn)
    }

    @Test
    fun `rememberMe defaults to true so pre-existing installs keep their current behavior`() {
        val store = TokenStore(FakeSharedPreferences())

        assertTrue(store.rememberMe)
    }

    @Test
    fun `rememberMe persists an explicit false`() {
        val store = TokenStore(FakeSharedPreferences())

        store.rememberMe = false

        assertFalse(store.rememberMe)
    }

    @Test
    fun `clearSession does not touch rememberMe`() {
        val store = TokenStore(FakeSharedPreferences())
        store.token = "abc123"
        store.rememberMe = false

        store.clearSession()

        assertFalse(store.rememberMe)
    }

    @Test
    fun `a second TokenStore over the same prefs sees what the first one wrote`() {
        val prefs = FakeSharedPreferences()
        val first = TokenStore(prefs)
        first.token = "abc123"
        first.deviceTokenId = "device-1"

        val second = TokenStore(prefs)

        assertEquals("abc123", second.token)
        assertEquals("device-1", second.deviceTokenId)
        assertTrue(second.isLoggedIn)
    }
}
