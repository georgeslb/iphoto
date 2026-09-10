package org.iphoto.mobile.data

import android.content.Context

/**
 * Sync target selection (which Space mirrors which on-device folder) and last-run bookkeeping.
 * Plain SharedPreferences, not TokenStore's EncryptedSharedPreferences - none of this is
 * sensitive (a space id, a SAF folder URI, a timestamp).
 */
class SyncPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    companion object {
        private const val PREFS_NAME = "iphoto_sync_prefs"
        private const val KEY_SPACE_ID = "space_id"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_FOLDER_NAME = "folder_name"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
    }
}
