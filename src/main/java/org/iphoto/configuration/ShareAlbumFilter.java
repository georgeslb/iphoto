package org.iphoto.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.AllArgsConstructor;
import org.iphoto.entities.ShareEntity;
import org.iphoto.repository.ShareRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

@Component
@AllArgsConstructor
public class ShareAlbumFilter
        extends OncePerRequestFilter {

    private final ShareRepository shareRepository;


    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String uri = request.getRequestURI();

        if (!uri.startsWith("/api/albums/share")) {
            filterChain.
                    doFilter(request, response);
            return;
        }

        String albumId = extractAlbumId(uri);

        Optional<ShareEntity> protectedShare =
                shareRepository.findById(UUID.fromString(albumId));

        // album public
        if (protectedShare.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        HttpSession session =
                request.getSession(false);

        if (session == null) {
            response.sendError(401);
            return;
        }

        String shareId =
                session.getAttribute("SHARE_ID").toString();

        if (shareId == null
                || !shareId.equals(
                protectedShare.get().getId().toString())) {

            response.sendError(403);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String extractAlbumId(String uri) {

        String[] parts = uri.split("/");

        return parts[3];
    }
}