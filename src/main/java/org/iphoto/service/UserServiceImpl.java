package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.exception.ImmichApiException;
import org.iphoto.repository.UserRepository;
import org.iphoto.service.immich.ImmichCredentialCipher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SpaceService spaceService;
    private final ImmichCredentialCipher immichCredentialCipher;

    @Override
    public List<UserEntity> findAll() {
        return userRepository.findAll();
    }

    @Override
    public Optional<UserEntity> findById(UUID id) {
        return userRepository.findById(id);
    }

    @Override
    public Optional<UserEntity> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    @Override
    @Transactional
    public UserEntity createUser(String username, String password, String role) {
        UserEntity user = userRepository.save(UserEntity.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .role(role).build());
        SpaceEntity defaultSpace = spaceService.createDefaultSpace(user);
        Set<SpaceEntity> spaces = new HashSet<>();
        spaces.add(defaultSpace);
        user.setSpaces(spaces);
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public void deleteUser(UUID id) {
        userRepository.findById(id).ifPresent(user -> {
            spaceService.getAllSpaces(user).forEach(space -> spaceService.deleteSpace(space.id()));
            userRepository.deleteById(id);
        });
    }

    @Override
    @Transactional
    public Optional<UserEntity> resetPassword(UUID id, String newPassword) {
        return userRepository.findById(id).map(user -> {
            String oldHash = user.getPassword();
            String newHash = passwordEncoder.encode(newPassword);
            reEncryptImmichApiKey(user, oldHash, newHash);
            user.setPassword(newHash);
            return userRepository.save(user);
        });
    }

    // The stored Immich API key is encrypted with a key derived from the user's *current* bcrypt
    // hash (see ImmichCredentialCipher) - if the password changes without this, the ciphertext
    // becomes permanently undecryptable. Re-encrypt in place so it survives the reset; if
    // decryption with the old hash unexpectedly fails, clear the stored key rather than leave a
    // silently-broken one behind (the user just has to re-enter it once).
    private void reEncryptImmichApiKey(UserEntity user, String oldHash, String newHash) {
        if (!StringUtils.hasText(user.getImmichApiKeyEncrypted())) return;
        try {
            String apiKey = immichCredentialCipher.decrypt(user.getImmichApiKeyIv(), user.getImmichApiKeyEncrypted(), oldHash);
            ImmichCredentialCipher.Encrypted reEncrypted = immichCredentialCipher.encrypt(apiKey, newHash);
            user.setImmichApiKeyIv(reEncrypted.ivBase64());
            user.setImmichApiKeyEncrypted(reEncrypted.ciphertextBase64());
        } catch (Exception e) {
            log.warn("Could not re-encrypt stored Immich API key for user {} during password reset - clearing it, user must re-enter it", user.getId(), e);
            user.setImmichApiKeyIv(null);
            user.setImmichApiKeyEncrypted(null);
        }
    }

    @Override
    @Transactional
    public UserEntity updateImmichSettings(UUID userId, String baseUrl, String apiKey) {
        UserEntity user = userRepository.findById(userId).orElseThrow(() -> new NoSuchElementException("User not found: " + userId));
        user.setImmichBaseUrl(StringUtils.hasText(baseUrl) ? baseUrl.trim() : null);
        if (StringUtils.hasText(apiKey)) {
            ImmichCredentialCipher.Encrypted encrypted = immichCredentialCipher.encrypt(apiKey, user.getPassword());
            user.setImmichApiKeyIv(encrypted.ivBase64());
            user.setImmichApiKeyEncrypted(encrypted.ciphertextBase64());
        }
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public UserEntity clearImmichSettings(UUID userId) {
        UserEntity user = userRepository.findById(userId).orElseThrow(() -> new NoSuchElementException("User not found: " + userId));
        user.setImmichBaseUrl(null);
        user.setImmichApiKeyIv(null);
        user.setImmichApiKeyEncrypted(null);
        return userRepository.save(user);
    }

    @Override
    public Optional<ImmichCredentials> resolveImmichCredentials(UserEntity user) {
        if (!StringUtils.hasText(user.getImmichBaseUrl()) || !StringUtils.hasText(user.getImmichApiKeyEncrypted())) {
            return Optional.empty();
        }
        String apiKey = immichCredentialCipher.decrypt(user.getImmichApiKeyIv(), user.getImmichApiKeyEncrypted(), user.getPassword());
        return Optional.of(new ImmichCredentials(user.getImmichBaseUrl(), apiKey));
    }

    @Override
    public ImmichCredentials requireImmichCredentials(UserEntity user) {
        return resolveImmichCredentials(user)
                .orElseThrow(() -> new ImmichApiException("Immich is not configured for this account - set your Immich URL and API key first.", null));
    }
}
