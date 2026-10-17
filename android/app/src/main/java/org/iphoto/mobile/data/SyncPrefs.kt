package org.iphoto.mobile.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.iphoto.mobile.ui.sync.SelectedFolder

/**
 * Persists selected local folder tabs and upload history.
 * Plain SharedPreferences.
 */
class SyncPrefs internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) :
        this(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    fun saveSelectedFolders(folders: List<SelectedFolder>) {
        val folderSet = folders.map { "${it.uri}$FIELD_SEPARATOR${it.name}" }.toSet()
        prefs.edit().putStringSet(KEY_SELECTED_FOLDERS, folderSet).apply()
    }

    fun loadSelectedFolders(): List<SelectedFolder> {
        return prefs.getStringSet(KEY_SELECTED_FOLDERS, null)?.mapNotNull { raw ->
            val parts = raw.split(FIELD_SEPARATOR)
            if (parts.size != 2) return@mapNotNull null
            try {
                SelectedFolder(Uri.parse(parts[0]), parts[1])
            } catch (e: Exception) {
                null
            }
        } ?: emptyList()
    }

    fun recordUploadedAsset(folderUri: String, relativeDir: String, displayName: String) {
        val key = encodeUploadedKey(folderUri, relativeDir, displayName)
        val current = getUploadedAssetKeys().toMutableSet()
        current.add(key)
        prefs.edit().putStringSet(KEY_UPLOADED_ASSETS, current).commit()
    }

    fun removeUploadedAsset(folderUri: String, relativeDir: String, displayName: String) {
        val exactKey = encodeUploadedKey(folderUri, relativeDir, displayName)
        val decodedFolder = try {
            Uri.decode(folderUri) ?: folderUri
        } catch (_: Throwable) {
            folderUri
        }
        val decodedKey = encodeUploadedKey(decodedFolder, relativeDir, displayName)
        val current = getUploadedAssetKeys().toMutableSet()
        if (current.remove(exactKey) or current.remove(decodedKey)) {
            prefs.edit().putStringSet(KEY_UPLOADED_ASSETS, current).commit()
        }
    }

    fun removeUploadedAssetsForFolder(folderUri: String) {
        val decodedFolder = try {
            Uri.decode(folderUri) ?: folderUri
        } catch (_: Throwable) {
            folderUri
        }
        val exactPrefix = "$folderUri$FIELD_SEPARATOR"
        val decodedPrefix = "$decodedFolder$FIELD_SEPARATOR"
        val current = getUploadedAssetKeys().toMutableSet()
        val changed = current.removeAll { it.startsWith(exactPrefix) || it.startsWith(decodedPrefix) }
        if (changed) {
            prefs.edit().putStringSet(KEY_UPLOADED_ASSETS, current).commit()
        }
    }

    fun retainUploadedAssetsForFolder(folderUri: String, existingRelativeAndName: Set<Pair<String, String>>) {
        val decodedFolder = try {
            Uri.decode(folderUri) ?: folderUri
        } catch (_: Throwable) {
            folderUri
        }
        val exactPrefix = "$folderUri$FIELD_SEPARATOR"
        val decodedPrefix = "$decodedFolder$FIELD_SEPARATOR"
        val allowedKeys = existingRelativeAndName.flatMap { (rel, name) ->
            listOf(
                encodeUploadedKey(folderUri, rel, name),
                encodeUploadedKey(decodedFolder, rel, name)
            )
        }.toSet()
        val current = getUploadedAssetKeys().toMutableSet()
        val changed = current.removeAll { key ->
            (key.startsWith(exactPrefix) || key.startsWith(decodedPrefix)) && key !in allowedKeys
        }
        if (changed) {
            prefs.edit().putStringSet(KEY_UPLOADED_ASSETS, current).commit()
        }
    }

    /**
     * Updates the uploaded state of a batch of assets in [folderUri] based on the server's
     * `/api/photos/check-hashes` response. Assets with status `true` are marked as uploaded;
     * assets with status `false` have any stale uploaded marker removed.
     */
    fun applyBatchHashCheckStatuses(
        folderUri: String,
        statuses: List<Pair<Pair<String, String>, Boolean>>
    ) {
        if (statuses.isEmpty()) return
        val decodedFolder = try {
            Uri.decode(folderUri) ?: folderUri
        } catch (_: Throwable) {
            folderUri
        }
        val current = getUploadedAssetKeys().toMutableSet()
        var changed = false
        for ((relAndName, existsOnServer) in statuses) {
            val (relativeDir, displayName) = relAndName
            val exactKey = encodeUploadedKey(folderUri, relativeDir, displayName)
            val decodedKey = encodeUploadedKey(decodedFolder, relativeDir, displayName)
            if (existsOnServer) {
                if (current.add(exactKey)) {
                    changed = true
                }
            } else {
                if (current.remove(exactKey) or current.remove(decodedKey)) {
                    changed = true
                }
            }
        }
        if (changed) {
            prefs.edit().putStringSet(KEY_UPLOADED_ASSETS, current).commit()
        }
    }

    fun getUploadedAssetKeys(): Set<String> {
        val set = prefs.getStringSet(KEY_UPLOADED_ASSETS, null)
        return set?.toSet() ?: emptySet()
    }

    companion object {
        private const val PREFS_NAME = "iphoto_sync_prefs"
        private const val KEY_SELECTED_FOLDERS = "selected_folders"
        private const val KEY_UPLOADED_ASSETS = "uploaded_assets"
        private const val FIELD_SEPARATOR = '\u0001'

        fun encodeUploadedKey(folderUri: String, relativeDir: String, displayName: String): String =
            "$folderUri$FIELD_SEPARATOR$relativeDir$FIELD_SEPARATOR$displayName"
    }
}
