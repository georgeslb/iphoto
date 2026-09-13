package org.iphoto.mobile.data

import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Exercises GcmCipherCodec's actual encrypt/decrypt framing (IV handling, byte offsets, tag
 * verification) against a plain in-memory AES key - not an Android Keystore-backed one, since
 * "AndroidKeyStore" isn't a real provider in a JVM unit test. KeystoreEncryptedPreferences (which
 * supplies the real, Keystore-backed key in production) is exactly why this split exists: it lets
 * this class's own logic be genuinely verified here, with only key *sourcing* left untested.
 */
class GcmCipherCodecTest {

    private fun generateKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `decrypt reverses encrypt`() {
        val key = generateKey()

        val cipherText = GcmCipherCodec.encrypt(key, "super-secret-token")

        assertEquals("super-secret-token", GcmCipherCodec.decrypt(key, cipherText))
    }

    @Test
    fun `round-trips an empty string`() {
        val key = generateKey()

        val cipherText = GcmCipherCodec.encrypt(key, "")

        assertEquals("", GcmCipherCodec.decrypt(key, cipherText))
    }

    @Test
    fun `encrypting the same plaintext twice produces different ciphertext`() {
        val key = generateKey()

        val first = GcmCipherCodec.encrypt(key, "super-secret-token")
        val second = GcmCipherCodec.encrypt(key, "super-secret-token")

        // A fresh random IV per call (GCM's own requirement - reusing an IV under the same key is
        // what actually breaks GCM's security) means the ciphertext should never collide either.
        assertNotEquals(first, second)
        assertEquals("super-secret-token", GcmCipherCodec.decrypt(key, first))
        assertEquals("super-secret-token", GcmCipherCodec.decrypt(key, second))
    }

    @Test
    fun `decrypting with the wrong key fails instead of returning garbage`() {
        val cipherText = GcmCipherCodec.encrypt(generateKey(), "super-secret-token")

        assertThrows(AEADBadTagException::class.java) {
            GcmCipherCodec.decrypt(generateKey(), cipherText)
        }
    }

    @Test
    fun `decrypting tampered ciphertext fails instead of returning garbage`() {
        val key = generateKey()
        val cipherText = GcmCipherCodec.encrypt(key, "super-secret-token")
        val tampered = tamperLastByte(cipherText)

        assertThrows(AEADBadTagException::class.java) {
            GcmCipherCodec.decrypt(key, tampered)
        }
    }

    // Flips the last base64-decoded byte (part of the GCM tag) and re-encodes, to corrupt the
    // ciphertext in a way that stays validly Base64-decodable but must fail its tag check.
    private fun tamperLastByte(base64CipherText: String): String {
        val bytes = java.util.Base64.getDecoder().decode(base64CipherText)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }
}
