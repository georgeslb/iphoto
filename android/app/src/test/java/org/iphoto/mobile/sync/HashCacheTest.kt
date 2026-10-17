package org.iphoto.mobile.sync

import android.net.Uri
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HashCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.decode(any()) } answers { firstArg() }
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun `removeAll deletes cached MD5 hashes and persists to disk`() {
        val cacheFile = tempFolder.newFile("sync_hash_cache.json")
        val cache = HashCache(cacheFile)

        cache.put("content://folder/camera/1", 100L, 1000L, "md5-1")
        cache.put("content://folder/camera/2", 200L, 2000L, "md5-2")
        cache.persist()

        assertTrue(cache.removeAll(listOf("content://folder/camera/1")))
        assertNull(cache.get("content://folder/camera/1", 100L, 1000L))
        assertEquals("md5-2", cache.get("content://folder/camera/2", 200L, 2000L))

        // Reload from disk to verify persistence
        val reloaded = HashCache(cacheFile)
        assertNull(reloaded.get("content://folder/camera/1", 100L, 1000L))
        assertEquals("md5-2", reloaded.get("content://folder/camera/2", 200L, 2000L))
    }

    @Test
    fun `retainForFolder prunes missing files while preserving existing files and other folders`() {
        val cacheFile = tempFolder.newFile("sync_hash_cache.json")
        val cache = HashCache(cacheFile)

        cache.put("content://folder/camera/1", 100L, 1000L, "md5-1")
        cache.put("content://folder/camera/deleted", 150L, 1500L, "md5-deleted")
        cache.put("content://folder/screenshots/1", 300L, 3000L, "md5-other")
        cache.persist()

        assertTrue(cache.retainForFolder("content://folder/camera", setOf("content://folder/camera/1")))

        val reloaded = HashCache(cacheFile)
        assertEquals("md5-1", reloaded.get("content://folder/camera/1", 100L, 1000L))
        assertNull(reloaded.get("content://folder/camera/deleted", 150L, 1500L))
        assertEquals("md5-other", reloaded.get("content://folder/screenshots/1", 300L, 3000L))
    }

    @Test
    fun `removeForFolder removes all cached hashes belonging to the folder`() {
        val cacheFile = tempFolder.newFile("sync_hash_cache.json")
        val cache = HashCache(cacheFile)

        cache.put("content://folder/camera/1", 100L, 1000L, "md5-1")
        cache.put("content://folder/camera/2", 200L, 2000L, "md5-2")
        cache.put("content://folder/screenshots/1", 300L, 3000L, "md5-other")
        cache.persist()

        assertTrue(cache.removeForFolder("content://folder/camera"))

        val reloaded = HashCache(cacheFile)
        assertNull(reloaded.get("content://folder/camera/1", 100L, 1000L))
        assertNull(reloaded.get("content://folder/camera/2", 200L, 2000L))
        assertEquals("md5-other", reloaded.get("content://folder/screenshots/1", 300L, 3000L))
    }

    @Test
    fun `putHashes and getHashes persist both realMd5 and without-localization redactedMd5`() {
        val cacheFile = tempFolder.newFile("sync_hash_cache.json")
        val cache = HashCache(cacheFile)

        val dualHashes = AssetHashes(realMd5 = "real-md5-1", redactedMd5 = "redacted-md5-1")
        cache.putHashes("content://folder/camera/gps-photo", 500L, 5000L, dualHashes)
        cache.persist()

        val reloaded = HashCache(cacheFile)
        val loaded = reloaded.getHashes("content://folder/camera/gps-photo", 500L, 5000L)
        assertEquals(dualHashes, loaded)
        assertEquals(listOf("real-md5-1", "redacted-md5-1"), loaded?.allDistinct())
    }

    @Test
    fun `legacy cache entry without redactedHash returns null from getHashes until upgraded`() {
        val cacheFile = tempFolder.newFile("sync_hash_cache.json")
        val cache = HashCache(cacheFile)

        // Legacy entry without redactedHash
        cache.put("content://folder/camera/legacy", 100L, 1000L, "real-md5-only")
        cache.persist()

        val reloaded = HashCache(cacheFile)
        assertEquals("real-md5-only", reloaded.get("content://folder/camera/legacy", 100L, 1000L))
        assertNull(reloaded.getHashes("content://folder/camera/legacy", 100L, 1000L))
    }
}

