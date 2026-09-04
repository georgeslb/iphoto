package org.iphoto.model;

import java.time.LocalDateTime;
import java.util.UUID;

// Response to POST /api/auth/mobile/login. `token` is the raw device token, shown exactly once -
// the server never stores or returns it again after this. `user` is bundled so the app doesn't
// need a second round-trip to GET /api/user/me right after login.
public record DeviceTokenResponse(
        UUID id,
        String token,
        String deviceName,
        LocalDateTime createdAt,
        UserDto user
) {
}
