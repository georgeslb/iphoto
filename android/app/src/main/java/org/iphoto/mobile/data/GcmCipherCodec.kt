package org.iphoto.mobile.data

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES/GCM encrypt/decrypt framing (IV + ciphertext, Base64-encoded) - split out from
 * KeystoreEncryptedPreferences so this part (IV handling, tag verification, byte offsets - the
 * part actually worth unit-testing) can be exercised against a plain in-memory SecretKey in a
 * normal JVM test, without needing a real Android Keystore. See KeystoreEncryptedPreferences for
 * the Keystore-specific key management this is paired with in production.
 *
 * Deliberately `java.util.Base64` (real JDK code, available since this app's minSdk 26) rather
 * than `android.util.Base64` - the latter is one of the Android SDK stub methods a plain JVM unit
 * test can't actually execute (it would need Robolectric, or `isReturnDefaultValues` silently
 * turning every call into a no-op returning null).
 */
object GcmCipherCodec {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128

    fun encrypt(key: SecretKey, plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        // IV length isn't hardcoded to GCM's usual 12 bytes - storing its actual length keeps this
        // forward-compatible with a provider that ever picks a different size.
        val combined = ByteArray(4 + iv.size + cipherBytes.size)
        writeInt(combined, 0, iv.size)
        System.arraycopy(iv, 0, combined, 4, iv.size)
        System.arraycopy(cipherBytes, 0, combined, 4 + iv.size, cipherBytes.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    fun decrypt(key: SecretKey, stored: String): String {
        val combined = Base64.getDecoder().decode(stored)
        val ivSize = readInt(combined, 0)
        val iv = combined.copyOfRange(4, 4 + ivSize)
        val cipherBytes = combined.copyOfRange(4 + ivSize, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun readInt(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 24) or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)
}
