package org.iphoto.service;

import org.iphoto.entities.UserEntity;
import org.iphoto.model.ShareResponse;
import org.iphoto.model.request.ShareRequest;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public interface ShareService {

    ShareResponse createShare(UserEntity user, ShareRequest request);

    // Static, not proxied - a pure function with no dependencies, referenced elsewhere as
    // ShareService.computeUserMd5(...) (e.g. ImmichAlbumImportService).
    static String computeUserMd5(UUID userId) {
        return DigestUtils.md5DigestAsHex(userId.toString().getBytes(StandardCharsets.UTF_8));
    }

    // This method is intended for public/guest access to a share link, so it does not filter by
    // user — the guest resolves which user's share this is via userMd5, not via a login session.
    ShareResponse getShareDetails(String userMd5, String name);

    // This method is intended for public/guest access to a share link (password verification),
    // so it does not filter by user/space — a guest session has no matching UserEntity.
    ShareResponse getShareDetailsById(String shareId);

    // Same public/guest access note as above.
    String getSharePassword(String shareId);

    List<ShareResponse> getAllShares(UserEntity user);

    ShareResponse getShareById(UserEntity user, UUID id);

    void deleteShare(UserEntity user, UUID id);

    // findById here is @MainSpaceLimited, so this only ever finds/updates a share owned by the
    // current user — the same ownership guarantee deleteShare/getShareById already rely on.
    ShareResponse updateSharePassword(UserEntity user, UUID id, String newPassword);

    ShareResponse extendShare(UserEntity user, UUID id, int days);

    ShareResponse updateShare(UserEntity user, UUID id, org.iphoto.model.request.ShareUpdateRequest request);
}
