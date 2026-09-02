package org.iphoto.service.immich;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

// Encrypts/decrypts a user's Immich API key at rest, per explicit product decision: the key
// material is derived from the user's own `UserEntity.password` column (their bcrypt hash), NOT
// from their raw password via a real KDF (PBKDF2/Argon2/scrypt). This is a deliberate simplicity
// trade-off - see UserEntity's immichApiKeyEncrypted field comment:
//   - We never need the user's raw password, only the hash already sitting in the row, so any
//     thread that can load a UserEntity (including a background SYNC_FROM_IMMICH/UPLOAD_FROM_IMMICH task with no
//     HTTP session/SecurityContext) can decrypt without any session-caching or "re-enter your
//     password to import" plumbing.
//   - The honest trade-off: this is obfuscation-at-rest, not a real password-based secret. Anyone
//     with read access to the `users` table can derive the same AES key from the bcrypt hash
//     sitting in the very next column and decrypt every stored API key. It only protects against
//     the API key being visible in application logs/API responses/casual DB browsing, not against
//     someone who already has full DB access - which, for a self-hosted single-tenant-ish app
//     like this one, was judged an acceptable trade for not having to build session-based key
//     caching or re-auth-to-import flows.
@Component
public class ImmichCredentialCipher {

    private static final String CIPHER_ALGO = "AES/GCM/NoPadding";
    private static final String KEY_ALGO = "AES";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    public record Encrypted(String ivBase64, String ciphertextBase64) {
    }

    public Encrypted encrypt(String plaintext, String bcryptHash) {
        try {
            byte[] iv = new byte[IV_BYTES];
            SecureRandom.getInstanceStrong().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(bcryptHash), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(ciphertext));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to encrypt Immich API key", e);
        }
    }

    public String decrypt(String ivBase64, String ciphertextBase64, String bcryptHash) {
        try {
            byte[] iv = Base64.getDecoder().decode(ivBase64);
            byte[] ciphertext = Base64.getDecoder().decode(ciphertextBase64);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(bcryptHash), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Most commonly: decrypting with a password hash different from the one used to
            // encrypt (e.g. a password reset that didn't go through UserService#resetPassword's
            // re-encryption step). Surfaced as a plain runtime exception - callers treat a failed
            // Immich action the same way regardless of the underlying reason.
            throw new IllegalStateException("Failed to decrypt Immich API key", e);
        }
    }

    private static SecretKeySpec deriveKey(String bcryptHash) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bcryptHash.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, KEY_ALGO);
    }
}
