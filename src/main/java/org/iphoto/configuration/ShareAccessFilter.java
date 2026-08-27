package org.iphoto.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.AllArgsConstructor;
import org.iphoto.entities.ShareEntity;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.ShareRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A guest holding a share session (ROLE_SHARE, established in ShareController when accessing a
 * share link) is, per SecurityConfig, "authenticated" the same as any real user — so
 * anyRequest().authenticated() alone lets them reach /api/albums/share/** and
 * /api/photos/{id}/(thumbnail|original|stream). Those endpoints don't otherwise check ownership
 * (photo/album repositories aren't @MainSpaceLimited-filterable for a guest — there's no matching
 * UserEntity to filter by), so without this filter a guest could reach ANY album/photo by UUID,
 * not just the one their share link grants. This filter is the actual authorization boundary for
 * guests: it confirms the requested album — or the album a requested photo belongs to — matches
 * the ShareEntity the guest's session was authenticated against.
 *
 * Non-guest requests (real authenticated users, or requests with no ROLE_SHARE authority) pass
 * through unchanged; their authorization is handled elsewhere.
 */
@Component
@AllArgsConstructor
public class ShareAccessFilter extends OncePerRequestFilter {

    private final ShareRepository shareRepository;
    private final PhotoRepository photoRepository;

    private static final Pattern ALBUM_SHARE_PATTERN =
            Pattern.compile("^/api/albums/share/([0-9a-fA-F-]{36})(?:/photos(?:/([0-9a-fA-F-]{36}))?)?$");
    private static final Pattern PHOTO_ASSET_PATTERN =
            Pattern.compile("^/api/photos/([0-9a-fA-F-]{36})(?:/.*)?$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String uri = request.getRequestURI();
        boolean isAlbumShareRequest = uri.startsWith("/api/albums/share/");
        boolean isPhotoAssetRequest = uri.startsWith("/api/photos/");

        if (!isAlbumShareRequest && !isPhotoAssetRequest) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean isShareGuest = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_SHARE".equals(authority.getAuthority()));

        if (!isShareGuest) {
            filterChain.doFilter(request, response);
            return;
        }

        HttpSession session = request.getSession(false);
        Object shareIdAttribute = session != null ? session.getAttribute("SHARE_ID") : null;

        if (shareIdAttribute == null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        Optional<ShareEntity> share = shareRepository.findByIdIgnoringSpace(UUID.fromString(shareIdAttribute.toString()));

        boolean shareExpired = share.isPresent()
                && share.get().getAvailableUntil() != null
                && share.get().getAvailableUntil().isBefore(LocalDateTime.now());

        if (share.isEmpty() || shareExpired) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        UUID authorizedAlbumId = share.get().getAlbum().getId();

        if (isAlbumShareRequest) {
            Matcher matcher = ALBUM_SHARE_PATTERN.matcher(uri);
            if (!matcher.matches() || !authorizedAlbumId.toString().equalsIgnoreCase(matcher.group(1))) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        } else {
            Matcher matcher = PHOTO_ASSET_PATTERN.matcher(uri);
            if (!matcher.matches()) {
                // Not a "/api/photos/{uuid}(/...)" shaped path (e.g. /api/photos/selected) —
                // nothing for this filter to authorize; let it through to the controller, which
                // will reject it on its own terms (it expects a real authenticated user).
                filterChain.doFilter(request, response);
                return;
            }
            UUID photoId = UUID.fromString(matcher.group(1));
            boolean photoInAuthorizedAlbum = photoRepository
                    .findByIdAndAlbums_IdAndIsGarbageFalse(photoId, authorizedAlbumId)
                    .isPresent();
            if (!photoInAuthorizedAlbum) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
