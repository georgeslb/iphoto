package org.iphoto.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.DeviceTokenEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.service.auth.DeviceTokenService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

// Establishes authentication from a mobile device token (Authorization: Bearer <token>, issued by
// DeviceAuthController/DeviceTokenService), the counterpart to session-cookie auth for the React
// web app - a phone shouldn't have to re-login every 30 minutes (application.yml's session
// timeout). Structured like ShareAccessFilter (same OncePerRequestFilter/@Component style), but
// ShareAccessFilter *authorizes* an already-established authentication; this filter *establishes*
// one, so it's wired before it in SecurityConfig.
//
// No Authorization header -> pass through untouched, letting session-cookie/permitAll rules
// handle the request exactly as today. A header that IS present but doesn't resolve to an active
// token fails hard (401) rather than silently falling back to anonymous - an invalid Bearer token
// should never be treated the same as "no token supplied."
@Component
@AllArgsConstructor
@Slf4j
public class DeviceTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final DeviceTokenService deviceTokenService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawToken = header.substring(BEARER_PREFIX.length()).trim();
        Optional<DeviceTokenEntity> deviceToken = deviceTokenService.findActiveByRawToken(rawToken);

        if (deviceToken.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        UserEntity user = deviceToken.get().getUser();
        // Same ROLE_* authority shape UserDetailsServiceImpl builds for session-cookie auth, so
        // every existing controller (AbstractBaseController.getCurrentUser() and everything built
        // on it, plus role checks like /api/admin/** -> hasRole("ADMIN")) works unchanged for
        // Bearer-authenticated requests. The extra DEVICE_AUTH authority (see DeviceAuthContext)
        // is new: it's what lets SpaceService#assertSyncWritable tell "the Android app" apart from
        // "a web session" for the block/unblock-on-sync feature - the token's own id goes in
        // `details` (not a claim in the authorities list) since it identifies the specific device,
        // not a role.
        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_" + user.getRole()),
                new SimpleGrantedAuthority(DeviceAuthContext.DEVICE_AUTH_AUTHORITY)
        );
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(user.getUsername(), null, authorities);
        authentication.setDetails(deviceToken.get().getId());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        try {
            deviceTokenService.recordUsage(deviceToken.get());
        } catch (Exception e) {
            log.warn("Failed to update lastUsedAt for device token {}", deviceToken.get().getId(), e);
        }

        filterChain.doFilter(request, response);
    }
}
