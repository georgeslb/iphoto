package org.iphoto.mobile.data

import org.iphoto.mobile.data.SyncPrefs.ExcludedAsset
import org.iphoto.mobile.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the "Exclude from sync" bookkeeping (excludeAsset/includeAsset/excludedHashes) added
 * alongside SpaceDetailScreen's exclude action and SyncScreen's "Excluded from sync" list - in
 * particular the hand-rolled encode/decode round trip (SharedPreferences' String set has no room
 * for a data class, and this module otherwise has no need for a JSON dependency).
 */
class SyncPrefsTest {

    private fun newPrefs(prefs: FakeSharedPreferences = FakeSharedPreferences()): SyncPrefs = SyncPrefs(prefs)

    @Test
    fun `starts with nothing excluded`() {
        val syncPrefs = newPrefs()

        assertTrue(syncPrefs.excludedAssets.isEmpty())
        assertTrue(syncPrefs.excludedHashes.isEmpty())
    }

    @Test
    fun `excludeAsset round-trips hash, title and photoId`() {
        val syncPrefs = newPrefs()

        syncPrefs.excludeAsset("hash-1", "IMG_0001.jpg", "photo-1")

        assertEquals(setOf(ExcludedAsset("hash-1", "IMG_0001.jpg", "photo-1")), syncPrefs.excludedAssets)
        assertEquals(setOf("hash-1"), syncPrefs.excludedHashes)
    }

    @Test
    fun `excludeAsset accumulates multiple entries`() {
        val syncPrefs = newPrefs()

        syncPrefs.excludeAsset("hash-1", "a.jpg", "photo-1")
        syncPrefs.excludeAsset("hash-2", "b.jpg", "photo-2")

        assertEquals(setOf("hash-1", "hash-2"), syncPrefs.excludedHashes)
    }

    @Test
    fun `excludeAsset replaces an existing entry for the same hash instead of duplicating it`() {
        val syncPrefs = newPrefs()
        syncPrefs.excludeAsset("hash-1", "old-name.jpg", "photo-1")

        syncPrefs.excludeAsset("hash-1", "renamed.jpg", "photo-1")

        assertEquals(setOf(ExcludedAsset("hash-1", "renamed.jpg", "photo-1")), syncPrefs.excludedAssets)
    }

    @Test
    fun `includeAsset removes only the matching hash`() {
        val syncPrefs = newPrefs()
        syncPrefs.excludeAsset("hash-1", "a.jpg", "photo-1")
        syncPrefs.excludeAsset("hash-2", "b.jpg", "photo-2")

        syncPrefs.includeAsset("hash-1")

        assertEquals(setOf("hash-2"), syncPrefs.excludedHashes)
    }

    @Test
    fun `includeAsset for an unknown hash is a no-op`() {
        val syncPrefs = newPrefs()
        syncPrefs.excludeAsset("hash-1", "a.jpg", "photo-1")

        syncPrefs.includeAsset("does-not-exist")

        assertEquals(setOf("hash-1"), syncPrefs.excludedHashes)
    }

    @Test
    fun `a second SyncPrefs over the same underlying prefs sees excluded assets the first one wrote`() {
        val prefs = FakeSharedPreferences()
        val first = newPrefs(prefs)
        first.excludeAsset("hash-1", "a.jpg", "photo-1")

        val second = newPrefs(prefs)

        assertEquals(setOf("hash-1"), second.excludedHashes)
    }

    @Test
    fun `a title containing the internal field separator character still round-trips`() {
        //  is control-character territory - no real photo title would contain it, but a
        // decode bug here should fail loudly in a test rather than corrupt real prefs data.
        val syncPrefs = newPrefs()

        syncPrefs.excludeAsset("hash-1", "totally,normal|title.jpg", "photo-1")

        assertEquals("totally,normal|title.jpg", syncPrefs.excludedAssets.single().title)
    }
}
