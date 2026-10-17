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
}
