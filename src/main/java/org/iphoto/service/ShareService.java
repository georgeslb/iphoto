package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.ShareEntity;
import org.iphoto.entities.UserEntity; // Import UserEntity
import org.iphoto.model.request.ShareRequest;
import org.iphoto.model.ShareResponse;
import org.iphoto.repository.AlbumRepository;
import org.iphoto.repository.ShareRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
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
    private final SpaceService spaceService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(); // Use a single instance

    @Transactional
    public ShareResponse createShare(UserEntity user, ShareRequest request) {
        if (shareRepository.findByName(request.name()).isPresent()) {
            throw new IllegalArgumentException("Share with name '" + request.name() + "' already exists for this user.");
        }

        return  albumRepository.findById(request.albumId()) // Ensure album belongs to the user
                .map(album -> {
                    ShareEntity share =
                            ShareEntity.builder().space(spaceService.getDefaultSpaceForUser(user)).name(request.name())
                            .userMd5(computeUserMd5(user.getId()))
                            .availableUntil(request.daysAvailable() > 0 ? LocalDateTime.now().plusDays(request.daysAvailable()) : null)
                            .album(album)
                            .paswword(encryptPassword(request.password())).build();
                    return shareRepository.save(share);
                })
                .map(this::buildShareResponse)
                .orElseThrow(() -> new IllegalArgumentException("Album does not exist or does not belong to the user."));
    }

    public static String computeUserMd5(UUID userId) {
        return DigestUtils.md5DigestAsHex(userId.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String encryptPassword (String password) {
        if (!StringUtils.hasText(password)) {
            return null; // No password provided, so no encryption needed
        }
        return passwordEncoder.encode(password); // Encrypt the provided password
    }

    // This method is intended for public/guest access to a share link, so it does not filter by
    // user — the guest resolves which user's share this is via userMd5, not via a login session.
    public ShareResponse getShareDetails(String userMd5, String name) {
        ShareEntity shareEntity = shareRepository.findByUserMd5AndName(userMd5, name)
                .orElseThrow(() -> new NoSuchElementException("Share link not found."));

        return buildShareResponse(shareEntity);
    }

    private ShareResponse buildShareResponse(ShareEntity shareEntity) {
        // Check for password protection
        boolean isPasswordProtected = StringUtils.hasText(shareEntity.getPaswword());
        boolean isExpired = shareEntity.getAvailableUntil() != null && shareEntity.getAvailableUntil().isBefore(LocalDateTime.now());
        return new ShareResponse(shareEntity.getId().toString(), shareEntity.getName(), shareEntity.getAlbum().getId(), isPasswordProtected, isExpired, shareEntity.getUserMd5());
    }

    // This method is intended for public/guest access to a share link (password verification),
    // so it does not filter by user/space — a guest session has no matching UserEntity.
    public ShareResponse getShareDetailsById(String shareId) {
        ShareEntity shareEntity = shareRepository.findByIdIgnoringSpace(UUID.fromString(shareId))
                .orElseThrow(() -> new NoSuchElementException("Share link not found."));

        return buildShareResponse(shareEntity);
    }

    // Same public/guest access note as above.
    public String getSharePassword(String shareId) {
        ShareEntity shareEntity = shareRepository.findByIdIgnoringSpace(UUID.fromString(shareId))
                .orElseThrow(() -> new NoSuchElementException("Share link not found."));

        return shareEntity.getPaswword();
    }

    public List<ShareResponse> getAllShares(UserEntity user) {
        return shareRepository.findAll()
                .stream()
                .map(this::buildShareResponse)
                .collect(Collectors.toList());
    }

    public ShareResponse getShareById(UserEntity user, UUID id) {
        return shareRepository.findById(id)
                .map(this::buildShareResponse)
                .orElseThrow(() -> new NoSuchElementException("Share link not found or does not belong to the user."));
    }

    @Transactional
    public void deleteShare(UserEntity user, UUID id) {
        ShareEntity share = shareRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Share link not found or does not belong to the user."));
        shareRepository.delete(share);
    }

    // findById here is @MainSpaceLimited, so this only ever finds/updates a share owned by the
    // current user — the same ownership guarantee deleteShare/getShareById already rely on.
    @Transactional
    public ShareResponse updateSharePassword(UserEntity user, UUID id, String newPassword) {
        ShareEntity share = shareRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Share link not found or does not belong to the user."));
        share.setPaswword(encryptPassword(newPassword));
        return buildShareResponse(shareRepository.save(share));
    }
}
