package org.iphoto.mobile.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Holds the server URL and the mobile device token. Backed by EncryptedSharedPreferences
 * (AES-256-GCM, key in the Android Keystore) - see backup_rules.xml / data_extraction_rules.xml,
 * which exclude this same file from backup/device-transfer since the Keystore key never travels.
 */
class TokenStore(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var deviceTokenId: String?
        get() = prefs.getString(KEY_DEVICE_TOKEN_ID, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_TOKEN_ID, value).apply()

    val isLoggedIn: Boolean
        get() = !token.isNullOrEmpty()

    fun clearSession() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_DEVICE_TOKEN_ID)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "iphoto_secure_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_DEVICE_TOKEN_ID = "device_token_id"
    }
}
