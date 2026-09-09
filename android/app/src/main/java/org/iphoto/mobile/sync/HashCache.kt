package org.iphoto.mobile.sync

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Caches a local file's content hash keyed by (uri, size, lastModified), so a sync doesn't
 * re-hash every photo in the folder on every single run - only files that are new or have
 * actually changed on disk get re-read. Sync only ever runs on an explicit button tap, but
 * re-hashing an entire camera roll (including large videos) on every tap would still be a real,
 * avoidable battery/time cost.
 */
class HashCache(context: Context) {
    private val file = File(context.applicationContext.filesDir, "sync_hash_cache.json")
    private val gson = Gson()
    private val type = object : TypeToken<MutableMap<String, CachedHash>>() {}.type

    private val entries: MutableMap<String, CachedHash> by lazy { load() }

    private fun load(): MutableMap<String, CachedHash> {
        if (!file.exists()) return mutableMapOf()
        return try {
            gson.fromJson<MutableMap<String, CachedHash>>(file.readText(), type) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    @Synchronized
    fun get(uri: String, size: Long, lastModified: Long): String? {
        val cached = entries[uri] ?: return null
        return if (cached.size == size && cached.lastModified == lastModified) cached.hash else null
    }

    @Synchronized
    fun put(uri: String, size: Long, lastModified: Long, hash: String) {
        entries[uri] = CachedHash(size, lastModified, hash)
    }

    /** Written once per sync run rather than on every [put], to keep disk churn low. */
    @Synchronized
    fun persist() {
        file.writeText(gson.toJson(entries))
    }

    private data class CachedHash(val size: Long, val lastModified: Long, val hash: String)
}
