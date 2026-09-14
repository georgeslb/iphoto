package org.iphoto.configuration;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

// Reads "was this request authenticated as a mobile device, and which one" off the current
// SecurityContext - the piece SpaceService#assertSyncWritable and SpaceController's sync-lock
// endpoints need to tell an Android app request apart from a web-session one. A plain static
// utility (no DI) so it can be called from a service layer without wiring a dependency back onto
// DeviceTokenAuthenticationFilter or DeviceTokenService - avoids a circular bean dependency
// (DeviceTokenServiceImpl already needs to reach SpaceService, the other direction, to clear a
// lock when a device is revoked - see SpaceSyncLockListener).
public final class DeviceAuthContext {

    // Marker authority DeviceTokenAuthenticationFilter adds alongside the user's normal ROLE_*
    // one, purely so this class (and anything else that ever needs it) can tell "authenticated via
    // Bearer device token" apart from "authenticated via web session cookie" without either of
    // them needing to know about the other.
    public static final String DEVICE_AUTH_AUTHORITY = "DEVICE_AUTH";

    private DeviceAuthContext() {
    }

    public static Optional<UUID> currentDeviceTokenId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Optional.empty();
        }
        boolean isDeviceAuth = authentication.getAuthorities().stream()
                .anyMatch(authority -> DEVICE_AUTH_AUTHORITY.equals(authority.getAuthority()));
        if (!isDeviceAuth) {
            return Optional.empty();
        }
        return authentication.getDetails() instanceof UUID deviceTokenId
                ? Optional.of(deviceTokenId)
                : Optional.empty();
    }
}
