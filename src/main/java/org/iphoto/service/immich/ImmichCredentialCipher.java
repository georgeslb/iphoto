package org.iphoto.service.immich;

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
public interface ImmichCredentialCipher {

    record Encrypted(String ivBase64, String ciphertextBase64) {
    }

    Encrypted encrypt(String plaintext, String bcryptHash);

    String decrypt(String ivBase64, String ciphertextBase64, String bcryptHash);
}
