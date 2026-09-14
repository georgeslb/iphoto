package org.iphoto.mobile.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A [SharedPreferences] decorator that transparently encrypts/decrypts String values with an
 * AES-256-GCM key held in the Android Keystore - replaces
 * androidx.security:security-crypto's `EncryptedSharedPreferences`, whose APIs
 * (`EncryptedSharedPreferences`, `MasterKey`) were deprecated upstream in favor of exactly this
 * (see TokenStore's kdoc). The key never leaves the Keystore; only encrypted values are ever
 * written to disk, via the plain [delegate] SharedPreferences.
 *
 * Only VALUES are encrypted, not key names - unlike EncryptedSharedPreferences, which also
 * encrypts key names deterministically. TokenStore's own keys ("token", "server_url",
 * "device_token_id") aren't sensitive by themselves, only the values stored under them are, so
 * this trades that (minor) extra hardening for staying a plain string-keyed lookup rather than
 * needing a second layer just to find the right encrypted key again.
 *
 * The actual encrypt/decrypt framing (IV handling, Base64) lives in [GcmCipherCodec] so it can be
 * unit-tested without a real Keystore - this class only owns getting the Keystore-backed key and
 * wiring it into [GcmCipherCodec], plus the SharedPreferences plumbing. Only the String get/put
 * path is implemented, the only type TokenStore stores.
 */
class KeystoreEncryptedPreferences(private val delegate: SharedPreferences) : SharedPreferences {

    private val secretKey: SecretKey by lazy { getOrCreateKey() }

    override fun getString(key: String?, defValue: String?): String? {
        val stored = delegate.getString(key, null) ?: return defValue
        return try {
            GcmCipherCodec.decrypt(secretKey, stored)
        } catch (e: Exception) {
            // A value this Keystore key can no longer decrypt (key wiped by a factory reset, or
            // this is data from before this class existed) is unrecoverable by definition - treat
            // it the same as "nothing stored" rather than crashing the caller. For TokenStore this
            // just means the user has to log in again.
            defValue
        }
    }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun contains(key: String?): Boolean = delegate.contains(key)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = delegate.registerOnSharedPreferenceChangeListener(listener)

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = delegate.unregisterOnSharedPreferenceChangeListener(listener)

    override fun getAll(): MutableMap<String, *> =
        throw UnsupportedOperationException("KeystoreEncryptedPreferences: getAll is not used by TokenStore")

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        throw UnsupportedOperationException("KeystoreEncryptedPreferences: string sets are not used by TokenStore")

    override fun getInt(key: String?, defValue: Int): Int =
        throw UnsupportedOperationException("KeystoreEncryptedPreferences: ints are not used by TokenStore")

    override fun getLong(key: String?, defValue: Long): Long =
        throw UnsupportedOperationException("KeystoreEncryptedPreferences: longs are not used by TokenStore")

    override fun getFloat(key: String?, defValue: Float): Float =
        throw UnsupportedOperationException("KeystoreEncryptedPreferences: floats are not used by TokenStore")

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        throw UnsupportedOperationException("KeystoreEncryptedPreferences: booleans are not used by TokenStore")

    private inner class Editor : SharedPreferences.Editor {
        private val delegateEditor = delegate.edit()

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            delegateEditor.putString(key, value?.let { GcmCipherCodec.encrypt(secretKey, it) })
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply { delegateEditor.remove(key) }

        override fun clear(): SharedPreferences.Editor = apply { delegateEditor.clear() }

        override fun commit(): Boolean = delegateEditor.commit()

        override fun apply() = delegateEditor.apply()

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            throw UnsupportedOperationException("KeystoreEncryptedPreferences: string sets are not used by TokenStore")

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
            throw UnsupportedOperationException("KeystoreEncryptedPreferences: ints are not used by TokenStore")

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
            throw UnsupportedOperationException("KeystoreEncryptedPreferences: longs are not used by TokenStore")

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
            throw UnsupportedOperationException("KeystoreEncryptedPreferences: floats are not used by TokenStore")

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            throw UnsupportedOperationException("KeystoreEncryptedPreferences: booleans are not used by TokenStore")
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "iphoto_token_store_key"
    }
}
