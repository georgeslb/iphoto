package org.iphoto.service;

import org.iphoto.entities.UserEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserService {

    // Per-user Immich connection, decrypted and ready to use - see UserEntity's field comment and
    // ImmichCredentialCipher for how/why this is derived from the stored bcrypt hash rather than
    // the raw password.
    record ImmichCredentials(String baseUrl, String apiKey) {
    }

    List<UserEntity> findAll();

    Optional<UserEntity> findById(UUID id);

    Optional<UserEntity> findByUsername(String username);

    UserEntity createUser(String username, String password, String role);

    void deleteUser(UUID id);

    Optional<UserEntity> resetPassword(UUID id, String newPassword);

    Optional<UserEntity> updateDuplicationRegrouping(UUID id, boolean enabled);

    // ---------------------------------------------------------------
    // Per-user Immich settings (see specification.md)
    // ---------------------------------------------------------------

    // A blank/omitted apiKey means "keep the currently stored key, just update baseUrl" - the
    // frontend never gets the decrypted key back to redisplay, so a settings form necessarily
    // submits blank for "unchanged". Use clearImmichSettings for an explicit disconnect.
    UserEntity updateImmichSettings(UUID userId, String baseUrl, String apiKey);

    UserEntity clearImmichSettings(UUID userId);

    Optional<ImmichCredentials> resolveImmichCredentials(UserEntity user);

    ImmichCredentials requireImmichCredentials(UserEntity user);
}
