package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.ShareEntity;
import org.iphoto.model.ShareRequest;
import org.iphoto.model.ShareResponse;
import org.iphoto.repository.AlbumRepository;
import org.iphoto.repository.ShareRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShareService {

    private final ShareRepository shareRepository;
    private final AlbumRepository albumRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(); // Use a single instance

    @Transactional
    public ShareResponse createShare(ShareRequest request) {
        if (shareRepository.findByName(request.name()).isPresent()) {
            throw new IllegalArgumentException("Share with name '" + request.name() + "' already exists.");
        }

        return  albumRepository.findById(request.albumId())
                .map(album -> {
                    ShareEntity share = new ShareEntity();
                    share.setName(request.name());
                    share.setAvailableUntil(request.daysAvailable() > 0 ? LocalDateTime.now().plusDays(request.daysAvailable()) : null);
                    share.setAlbum(album);
                    share.setPaswword(encryptPassword(request.password()));
                    return shareRepository.save(share);
                })
                .map(this::buildShareResponse)
                .orElseThrow(() -> new IllegalArgumentException("Album does not exist"));
    }

    private String encryptPassword (String password) {
        if (!StringUtils.hasText(password)) {
            return null; // No password provided, so no encryption needed
        }
        return passwordEncoder.encode(password); // Encrypt the provided password
    }

    public ShareResponse getShareDetails(String name) {
        ShareEntity shareEntity = shareRepository.findByName(name)
                .orElseThrow(() -> new NoSuchElementException("Share link not found."));


        return buildShareResponse(shareEntity);
    }

    private ShareResponse buildShareResponse(ShareEntity shareEntity) {
        // Check for password protection
        boolean isPasswordProtected = StringUtils.hasText(shareEntity.getPaswword());
        boolean isExpired = shareEntity.getAvailableUntil() != null && shareEntity.getAvailableUntil().isBefore(LocalDateTime.now());
        return new ShareResponse(shareEntity.getId().toString(), shareEntity.getName(), shareEntity.getAlbum().getId(), isPasswordProtected, isExpired);
    }

    public ShareResponse getShareDetailsById(String shareId) {
        ShareEntity shareEntity = shareRepository.findById(UUID.fromString(shareId))
                .orElseThrow(() -> new NoSuchElementException("Share link not found."));

        return buildShareResponse(shareEntity);
    }

    public String getSharePassword(String shareId) {
        ShareEntity shareEntity = shareRepository.findById(UUID.fromString(shareId))
                .orElseThrow(() -> new NoSuchElementException("Share link not found."));

        return shareEntity.getPaswword();
    }

/*    public ShareAuthenticationResponse authenticateShare(String name, String password) {
        ShareEntity shareEntity = shareRepository.findByName(name)
                .orElseThrow(() -> new NoSuchElementException("Share link not found."));

        // Check for expiration
        if (shareEntity.getAvailableUntil() != null && shareEntity.getAvailableUntil().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("Share link has expired.");
        }

        // Check if password is required and matches
        if (StringUtils.hasText(shareEntity.getPaswword())) {
            if (passwordEncoder.matches(password, shareEntity.getPaswword())) {
                return new ShareAuthenticationResponse(shareEntity.getAlbum().getId(), "Authentication successful.");
            } else {
                throw new IllegalArgumentException("Incorrect password.");
            }
        } else {
            // If no password is set, and authentication is attempted, it's considered successful
            // as there's no password to check.
            return new ShareAuthenticationResponse(shareEntity.getAlbum().getId(), "No password required.");
        }
    }*/
}