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
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShareServiceImpl implements ShareService {

    private final ShareRepository shareRepository;
    private final AlbumRepository albumRepository;
    private final SpaceService spaceService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(); // Use a single instance

    @Override
    @Transactional
    public ShareResponse createShare(UserEntity user, ShareRequest request) {
        if (shareRepository.findByName(request.name()).isPresent()) {
            throw new IllegalArgumentException("Share with name '" + request.name() + "' already exists for this user.");
        }

        return  albumRepository.findById(request.albumId()) // Ensure album belongs to the user
                .map(album -> {
                    ShareEntity share =
                            ShareEntity.builder().space(spaceService.getDefaultSpaceForUser(user)).name(request.name())
                            .userMd5(ShareService.computeUserMd5(user.getId()))
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

    @Override
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

    @Override
    public ShareResponse getShareDetailsById(String shareId) {
        ShareEntity shareEntity = shareRepository.findByIdIgnoringSpace(UUID.fromString(shareId))
                .orElseThrow(() -> new NoSuchElementException("Share link not found."));

        return buildShareResponse(shareEntity);
    }

    @Override
    public String getSharePassword(String shareId) {
        ShareEntity shareEntity = shareRepository.findByIdIgnoringSpace(UUID.fromString(shareId))
                .orElseThrow(() -> new NoSuchElementException("Share link not found."));

        return shareEntity.getPaswword();
    }

    @Override
    public List<ShareResponse> getAllShares(UserEntity user) {
        return shareRepository.findAll()
                .stream()
                .map(this::buildShareResponse)
                .collect(Collectors.toList());
    }

    @Override
    public ShareResponse getShareById(UserEntity user, UUID id) {
        return shareRepository.findById(id)
                .map(this::buildShareResponse)
                .orElseThrow(() -> new NoSuchElementException("Share link not found or does not belong to the user."));
    }

    @Override
    @Transactional
    public void deleteShare(UserEntity user, UUID id) {
        ShareEntity share = shareRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Share link not found or does not belong to the user."));
        shareRepository.delete(share);
    }

    @Override
    @Transactional
    public ShareResponse updateSharePassword(UserEntity user, UUID id, String newPassword) {
        ShareEntity share = shareRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Share link not found or does not belong to the user."));
        share.setPaswword(encryptPassword(newPassword));
        return buildShareResponse(shareRepository.save(share));
    }
}
