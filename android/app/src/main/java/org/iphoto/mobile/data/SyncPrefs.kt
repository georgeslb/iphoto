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
    var excludedAssets: Set<TrackedAsset>
        get() = readTracked(KEY_EXCLUDED_ASSETS)
        private set(value) = writeTracked(KEY_EXCLUDED_ASSETS, value)

    val excludedHashes: Set<String> get() = excludedAssets.map { it.hash }.toSet()

    fun excludeAsset(hash: String, title: String, photoId: String) {
        excludedAssets = excludedAssets.filterNot { it.hash == hash }.toSet() + TrackedAsset(hash, title, photoId)
    }

    fun includeAsset(hash: String) {
        excludedAssets = excludedAssets.filterNot { it.hash == hash }.toSet()
    }

    // Photos moved out of the synced Space into the Main Space (SpaceDetailScreen's "Move to Main
    // Space" selection action, see PhotoApi#moveToMain) while that Space was synced to a device
    // folder. The move leaves the local file untouched too (same reasoning as excludedAssets - the
    // user relocated the photo, they didn't delete anything), and needs the same protection: without
    // recording it here, the next sync pass would see the still-present local file, find its hash no
    // longer active in *this* Space (it now belongs to Main), and re-upload it as if it were new -
    // duplicating a photo that already exists, just under a different Space now.
    var movedToMainAssets: Set<TrackedAsset>
        get() = readTracked(KEY_MOVED_TO_MAIN_ASSETS)
        private set(value) = writeTracked(KEY_MOVED_TO_MAIN_ASSETS, value)

    val movedToMainHashes: Set<String> get() = movedToMainAssets.map { it.hash }.toSet()

    fun markMovedToMain(hash: String, title: String, photoId: String) {
        movedToMainAssets = movedToMainAssets.filterNot { it.hash == hash }.toSet() + TrackedAsset(hash, title, photoId)
    }

    private fun readTracked(key: String): Set<TrackedAsset> =
        prefs.getStringSet(key, null).orEmpty().mapNotNull(::decodeTracked).toSet()

    private fun writeTracked(key: String, value: Set<TrackedAsset>) {
        prefs.edit().putStringSet(key, value.map(::encodeTracked).toSet()).apply()
    }

    /** A photo tracked by hash so a later sync pass leaves it alone - see [excludedAssets]/[movedToMainAssets]. */
    data class TrackedAsset(val hash: String, val title: String, val photoId: String)

    // Block/unblock-on-sync (see SyncViewModel): true once this device has actually confirmed the
    // server-side lock for the current pairing (spaceId/folderUri above) - NOT just "a pairing is
    // configured". Distinguishes two very different situations on the next app start:
    //   - lockConfirmed == false: this pairing was never successfully locked yet (e.g. chosen
    //     while offline) - just retry the lock silently, nothing to warn about.
    //   - lockConfirmed == true, but the server now reports the space unlocked: someone unlocked
    //     it from elsewhere (the web "I lost my phone" escape hatch, or this device's own token
    //     being revoked) - THAT'S "sync was stopped", worth an explicit warning rather than
    //     silently re-locking and carrying on as if nothing happened.
    // Cleared both when a remote-stop is detected and handled, and when the user deliberately
    // stops sync from this device itself (SyncViewModel#stopSync/unlockSpaceIfLocked) - a
    // self-initiated unlock is never something to warn the user about on their own device.
    var lockConfirmed: Boolean
        get() = prefs.getBoolean(KEY_LOCK_CONFIRMED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_CONFIRMED, value).apply()

    companion object {
        private const val PREFS_NAME = "iphoto_sync_prefs"
        private const val KEY_SPACE_ID = "space_id"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_FOLDER_NAME = "folder_name"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled"
        private const val KEY_EXCLUDED_ASSETS = "excluded_assets"
        private const val KEY_MOVED_TO_MAIN_ASSETS = "moved_to_main_assets"

        // Each entry is "hash<SEP>title<SEP>photoId" - a plain delimiter rather than a JSON
        // dependency this module otherwise has no need for. \u0001 (not a printable character)
        // is used specifically because a photo title can itself contain any of ",", "|" or similar.
        private const val FIELD_SEPARATOR = '\u0001'

        private fun encodeTracked(asset: TrackedAsset): String =
            "${asset.hash}$FIELD_SEPARATOR${asset.title}$FIELD_SEPARATOR${asset.photoId}"

        private fun decodeTracked(raw: String): TrackedAsset? {
            val parts = raw.split(FIELD_SEPARATOR)
            if (parts.size != 3) return null
            return TrackedAsset(hash = parts[0], title = parts[1], photoId = parts[2])
        }

        private const val KEY_LOCK_CONFIRMED = "lock_confirmed"
    }
}
