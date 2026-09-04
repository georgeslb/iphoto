package org.iphoto.model;

import java.time.LocalDateTime;
import java.util.UUID;

// One row in "manage devices" (GET /api/auth/mobile/devices) - deliberately never carries the
// token/tokenHash itself, only enough to let the user recognize and revoke a device.
public record DeviceDTO(
        UUID id,
        String deviceName,
        LocalDateTime createdAt,
        LocalDateTime lastUsedAt,
        boolean revoked
) {
}
