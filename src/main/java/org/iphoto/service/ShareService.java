package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.ShareEntity;
import org.iphoto.entities.UserEntity; // Import UserEntity
import org.iphoto.model.ShareRequest;
import org.iphoto.model.ShareResponse;
import org.iphoto.repository.AlbumRepository;
import org.iphoto.repository.ShareRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShareService {

    private final ShareRepository shareRepository;
    private final AlbumRepository albumRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(); // Use a single instance

    @Transactional
    public ShareResponse createShare(UserEntity user, ShareRequest request) {
        if (shareRepository.findByUserAndName(user, request.name()).isPresent()) {
            throw new IllegalArgumentException("Share with name '" + request.name() + "' already exists for this user.");
        }

        return  albumRepository.findByIdAndUser(request.albumId(), user) // Ensure album belongs to the user
                .map(album -> {
                    ShareEntity share =
                            ShareEntity.builder().user(user).name(request.name())
                            .availableUntil(request.daysAvailable() > 0 ? LocalDateTime.now().plusDays(request.daysAvailable()) : null)
                            .album(album)
                            .paswword(encryptPassword(request.password())).build();
                    return shareRepository.save(share);
                })
                .map(this::buildShareResponse)
                .orElseThrow(() -> new IllegalArgumentException("Album does not exist or does not belong to the user."));
    }

    private String encryptPassword (String password) {
        if (!StringUtils.hasText(password)) {
            return null; // No password provided, so no encryption needed
        }
        return passwordEncoder.encode(password); // Encrypt the provided password
    }

    public ShareResponse getShareDetails(String name) {
        // This method is intended for public access to a share link, so it does not filter by user.
        // The share link itself must be unique globally by name.
        ShareEntity shareEntity = shareRepository.findByName(name) // Assuming findByName still exists for public shares
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
        // This method is intended for public access to a share link, so it does not filter by user.
        ShareEntity shareEntity = shareRepository.findById(UUID.fromString(shareId))
                .orElseThrow(() -> new NoSuchElementException("Share link not found."));

        return buildShareResponse(shareEntity);
    }

    public String getSharePassword(String shareId) {
        // This method is intended for public access to a share link, so it does not filter by user.
        ShareEntity shareEntity = shareRepository.findById(UUID.fromString(shareId))
                .orElseThrow(() -> new NoSuchElementException("Share link not found."));

        return shareEntity.getPaswword();
    }

    public List<ShareResponse> getAllShares(UserEntity user) {
        return shareRepository.findAllByUser(user)
                .stream()
                .map(this::buildShareResponse)
                .collect(Collectors.toList());
    }

    public ShareResponse getShareById(UserEntity user, UUID id) {
        return shareRepository.findByIdAndUser(id, user)
                .map(this::buildShareResponse)
                .orElseThrow(() -> new NoSuchElementException("Share link not found or does not belong to the user."));
    }

    @Transactional
    public void deleteShare(UserEntity user, UUID id) {
        ShareEntity share = shareRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new NoSuchElementException("Share link not found or does not belong to the user."));
        shareRepository.delete(share);
    }
}