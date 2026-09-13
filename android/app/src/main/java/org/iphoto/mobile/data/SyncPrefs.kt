package org.iphoto.mobile.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Sync target selection (which Space mirrors which on-device folder) and last-run bookkeeping.
 * Plain SharedPreferences, not TokenStore's KeystoreEncryptedPreferences - none of this is
 * sensitive (a space id, a SAF folder URI, a timestamp).
 *
 * Takes the [SharedPreferences] directly for the same reason as TokenStore's own `internal`
 * constructor (see its kdoc) - so SyncPrefsTest can exercise the get/set contract below against a
 * plain in-memory fake, without Robolectric or a real Android Context.
 */
class SyncPrefs internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) :
        this(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    var spaceId: String?
        get() = prefs.getString(KEY_SPACE_ID, null)
        set(value) = prefs.edit().putString(KEY_SPACE_ID, value).apply()

    var folderUri: String?
        get() = prefs.getString(KEY_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_FOLDER_URI, value).apply()

    var folderDisplayName: String?
        get() = prefs.getString(KEY_FOLDER_NAME, null)
        set(value) = prefs.edit().putString(KEY_FOLDER_NAME, value).apply()

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_AT, value).apply()

    // Opt-in periodic background sync (SyncWorker.schedulePeriodic/cancelPeriodic) - off by
    // default, matching the feature's original "nothing happens unless you tap the button" design.
    var autoSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SYNC_ENABLED, value).apply()

    // Photos the user explicitly excluded from the synced Space (SpaceDetailScreen's "Exclude from
    // sync" selection action) - garbaged server-side right away, but deliberately never mirrored
    // into LocalTrash: excluding isn't deleting, the file is meant to stay on the phone untouched.
    // Tracked here (by content hash) so SyncRepository#computeDiff can keep leaving them alone on
    // every later pass too - without this, the next sync would either re-upload the still-present
    // local file (nothing stops toUpload) or auto-restore the Space photo (a garbage entry whose
    // hash matches a present local file is normally exactly what toRestore is for), undoing the
    // exclusion within one sync cycle.
    var excludedAssets: Set<ExcludedAsset>
        get() = prefs.getStringSet(KEY_EXCLUDED_ASSETS, null).orEmpty().mapNotNull(::decodeExcluded).toSet()
        private set(value) = prefs.edit()
            .putStringSet(KEY_EXCLUDED_ASSETS, value.map(::encodeExcluded).toSet())
            .apply()

    val excludedHashes: Set<String> get() = excludedAssets.map { it.hash }.toSet()

    fun excludeAsset(hash: String, title: String, photoId: String) {
        excludedAssets = excludedAssets.filterNot { it.hash == hash }.toSet() + ExcludedAsset(hash, title, photoId)
    }

    fun includeAsset(hash: String) {
        excludedAssets = excludedAssets.filterNot { it.hash == hash }.toSet()
    }

    /** A photo the user excluded from sync - see [excludedAssets]. */
    data class ExcludedAsset(val hash: String, val title: String, val photoId: String)

    companion object {
        private const val PREFS_NAME = "iphoto_sync_prefs"
        private const val KEY_SPACE_ID = "space_id"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_FOLDER_NAME = "folder_name"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled"
        private const val KEY_EXCLUDED_ASSETS = "excluded_assets"

        // Each entry is "hash<SEP>title<SEP>photoId" - a plain delimiter rather than a JSON
        // dependency this module otherwise has no need for. \u0001 (not a printable character)
        // is used specifically because a photo title can itself contain any of ",", "|" or similar.
        private const val FIELD_SEPARATOR = '\u0001'

        private fun encodeExcluded(asset: ExcludedAsset): String =
            "${asset.hash}$FIELD_SEPARATOR${asset.title}$FIELD_SEPARATOR${asset.photoId}"

        private fun decodeExcluded(raw: String): ExcludedAsset? {
            val parts = raw.split(FIELD_SEPARATOR)
            if (parts.size != 3) return null
            return ExcludedAsset(hash = parts[0], title = parts[1], photoId = parts[2])
        }
    }
}
