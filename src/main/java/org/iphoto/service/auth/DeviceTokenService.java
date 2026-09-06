package org.iphoto.service.auth;

import org.iphoto.entities.DeviceTokenEntity;
import org.iphoto.entities.UserEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Long-lived mobile device auth: unlike the web app's session-cookie login (30-minute idle
// timeout, application.yml), a phone should stay signed in until the user explicitly logs out.
// Issues an opaque random token per device/login and stores only its SHA-256 hash - never the
// plaintext - so a stolen DB dump can't be used to log in as anyone; every request looks the
// token up by its hash (DeviceTokenAuthenticationFilter), an O(1) unique-index hit. Deliberately
// NOT BCrypt (self-salted output isn't equality-lookupable) and NOT ImmichCredentialCipher's AES
// scheme (that one exists to recover the *plaintext* Immich API key later; a device token is
// already high-entropy random data and never needs to be recovered, only compared).
public interface DeviceTokenService {

    record IssuedToken(UUID id, String rawToken, String deviceName, LocalDateTime createdAt) {
    }

    IssuedToken issueToken(UserEntity user, String deviceName);

    Optional<DeviceTokenEntity> findActiveByRawToken(String rawToken);

    // Best-effort bookkeeping - callers (DeviceTokenAuthenticationFilter) should not fail a
    // request just because this write failed.
    void recordUsage(DeviceTokenEntity token);

    List<DeviceTokenEntity> listForUser(UUID userId);

    void revoke(UUID tokenId, UserEntity currentUser);
}
