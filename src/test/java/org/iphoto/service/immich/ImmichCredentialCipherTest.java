package org.iphoto.service.immich;

import org.iphoto.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Direct service-level test - a pure crypto utility with no HTTP endpoint of its own (see
// UserService, the only real caller, for how encrypt/decrypt are used against a stored bcrypt
// hash).
class ImmichCredentialCipherTest extends AbstractIntegrationTest {

    @Autowired
    private ImmichCredentialCipher immichCredentialCipher;

    @Test
    void decryptReturnsTheOriginalPlaintextEncryptedWithTheSameHash() {
        String bcryptHash = "$2a$10$fakeHashUsedOnlyAsAesKeyMaterialInThisTest";

        ImmichCredentialCipher.Encrypted encrypted = immichCredentialCipher.encrypt("my-immich-api-key", bcryptHash);
        String decrypted = immichCredentialCipher.decrypt(encrypted.ivBase64(), encrypted.ciphertextBase64(), bcryptHash);

        assertThat(decrypted).isEqualTo("my-immich-api-key");
        assertThat(encrypted.ivBase64()).isNotBlank();
        assertThat(encrypted.ciphertextBase64()).isNotBlank();
    }

    @Test
    void decryptWithADifferentHashFailsRatherThanSilentlyReturningGarbage() {
        String bcryptHash = "$2a$10$originalHash";
        ImmichCredentialCipher.Encrypted encrypted = immichCredentialCipher.encrypt("secret", bcryptHash);

        assertThatThrownBy(() -> immichCredentialCipher.decrypt(encrypted.ivBase64(), encrypted.ciphertextBase64(), "$2a$10$aDifferentHash"))
                .isInstanceOf(IllegalStateException.class);
    }
}
