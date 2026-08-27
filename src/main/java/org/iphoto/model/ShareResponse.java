package org.iphoto.model;

import java.util.UUID;

public record ShareResponse(
    String id,
    String name,
    UUID albumId,
    boolean isPasswordProtected,
    boolean expired,
    String userMd5
) {}
