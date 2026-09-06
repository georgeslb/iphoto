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

@Component
public class ImmichCredentialCipherImpl implements ImmichCredentialCipher {

    private static final String CIPHER_ALGO = "AES/GCM/NoPadding";
    private static final String KEY_ALGO = "AES";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    @Override
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

    @Override
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
