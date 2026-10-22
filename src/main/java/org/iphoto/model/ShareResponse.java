package org.iphoto.model;

import java.time.LocalDateTime;
import java.util.UUID;

public record ShareResponse(
    String id,
    String name,
    UUID albumId,
    boolean isPasswordProtected,
    boolean expired,
    String userMd5,
    LocalDateTime createdAt,
    LocalDateTime availableUntil
) {
    public ShareResponse(String id, String name, UUID albumId, boolean isPasswordProtected, boolean expired, String userMd5) {
        this(id, name, albumId, isPasswordProtected, expired, userMd5, null, null);
    }

    public ShareResponse(String id, String name, UUID albumId, boolean isPasswordProtected, boolean expired, String userMd5, LocalDateTime createdAt) {
        this(id, name, albumId, isPasswordProtected, expired, userMd5, createdAt, null);
    }
}
