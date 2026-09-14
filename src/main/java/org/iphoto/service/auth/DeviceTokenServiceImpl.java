package org.iphoto.service.auth;

import lombok.RequiredArgsConstructor;
import org.iphoto.entities.DeviceTokenEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.event.DeviceTokenRevokedEvent;
import org.iphoto.repository.DeviceTokenRepository;
import org.springframework.context.ApplicationEventPublisher;
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

@Service
@RequiredArgsConstructor
public class DeviceTokenServiceImpl implements DeviceTokenService {

    private static final String TOKEN_PREFIX = "iphoto_mobile_";
    private static final int TOKEN_RANDOM_BYTES = 32;

    private final DeviceTokenRepository deviceTokenRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
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

    @Override
    public Optional<DeviceTokenEntity> findActiveByRawToken(String rawToken) {
        return deviceTokenRepository.findByTokenHashAndRevokedAtIsNull(hash(rawToken));
    }

    @Override
    public void recordUsage(DeviceTokenEntity token) {
        token.setLastUsedAt(LocalDateTime.now());
        deviceTokenRepository.save(token);
    }

    @Override
    public List<DeviceTokenEntity> listForUser(UUID userId) {
        return deviceTokenRepository.findByUser_IdOrderByCreatedAtDesc(userId);
    }

    @Override
    @Transactional
    public void revoke(UUID tokenId, UserEntity currentUser) {
        DeviceTokenEntity token = deviceTokenRepository.findById(tokenId)
                .filter(t -> t.getUser().getId().equals(currentUser.getId()))
                .orElseThrow(NoSuchElementException::new);
        token.setRevokedAt(LocalDateTime.now());
        deviceTokenRepository.save(token);
        // "I lost my phone" escape hatch - see SpaceSyncLockListener.
        eventPublisher.publishEvent(new DeviceTokenRevokedEvent(token.getId()));
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
