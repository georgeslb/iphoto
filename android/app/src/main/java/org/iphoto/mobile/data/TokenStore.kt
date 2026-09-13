package org.iphoto.mobile.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Holds the server URL and the mobile device token. Backed by [KeystoreEncryptedPreferences]
 * (AES-256-GCM, key in the Android Keystore) - see backup_rules.xml / data_extraction_rules.xml,
 * which exclude this same file from backup/device-transfer since the Keystore key never travels.
 * A previous version of this class used androidx.security:security-crypto's
 * `EncryptedSharedPreferences`/`MasterKey` for the same purpose; those APIs were deprecated
 * upstream in favor of using the Keystore directly, which is what KeystoreEncryptedPreferences
 * now does by hand. Values encrypted under the old scheme can't be decrypted by the new one (see
 * KeystoreEncryptedPreferences#getString) - on an existing install this surfaces as simply being
 * logged out, not a crash.
 *
 * Takes the [SharedPreferences] directly rather than building it from a [Context] inline, so the
 * get/set/clear contract below can be exercised in a plain unit test against an in-memory fake
 * (see TokenStoreTest/FakeSharedPreferences) without needing Robolectric or a real Android
 * Keystore just to test this class's own logic. This constructor is `internal` rather than
 * private specifically so the test source set can reach it - it's not meant as a second public
 * way to construct a TokenStore in production code, which should always go through the
 * `Context` constructor below.
 */
class TokenStore internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(createEncryptedPrefs(context))

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

        private fun createEncryptedPrefs(context: Context): SharedPreferences =
            KeystoreEncryptedPreferences(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
