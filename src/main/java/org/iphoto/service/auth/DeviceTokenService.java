package org.iphoto.service.auth;

import lombok.RequiredArgsConstructor;
import org.iphoto.entities.DeviceTokenEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.repository.DeviceTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
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
@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    private static final String TOKEN_PREFIX = "iphoto_mobile_";
    private static final int TOKEN_RANDOM_BYTES = 32;

    private final DeviceTokenRepository deviceTokenRepository;

    public record IssuedToken(UUID id, String rawToken, String deviceName, LocalDateTime createdAt) {
    }

    public IssuedToken issueToken(UserEntity user, String deviceName) {
        byte[] randomBytes = new byte[TOKEN_RANDOM_BYTES];
        new SecureRandom().nextBytes(randomBytes);
        String rawToken = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        LocalDateTime now = LocalDateTime.now();
        DeviceTokenEntity entity = DeviceTokenEntity.builder()
                .user(user)
                .tokenHash(hash(rawToken))
                .deviceName(deviceName)
                .createdAt(now)
                .build();
        deviceTokenRepository.save(entity);

        return new IssuedToken(entity.getId(), rawToken, deviceName, now);
    }

    public Optional<DeviceTokenEntity> findActiveByRawToken(String rawToken) {
        return deviceTokenRepository.findByTokenHashAndRevokedAtIsNull(hash(rawToken));
    }

    // Best-effort bookkeeping - callers (DeviceTokenAuthenticationFilter) should not fail a
    // request just because this write failed.
    public void recordUsage(DeviceTokenEntity token) {
        token.setLastUsedAt(LocalDateTime.now());
        deviceTokenRepository.save(token);
    }

    public List<DeviceTokenEntity> listForUser(UUID userId) {
        return deviceTokenRepository.findByUser_IdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public void revoke(UUID tokenId, UserEntity currentUser) {
        DeviceTokenEntity token = deviceTokenRepository.findById(tokenId)
                .filter(t -> t.getUser().getId().equals(currentUser.getId()))
                .orElseThrow(NoSuchElementException::new);
        token.setRevokedAt(LocalDateTime.now());
        deviceTokenRepository.save(token);
    }

    private static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
