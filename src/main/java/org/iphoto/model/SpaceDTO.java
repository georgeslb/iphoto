package org.iphoto.model;

import java.time.LocalDateTime;
import java.util.UUID;

public record SpaceDTO(
    UUID id,
    String name,
    Integer photoCount,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    boolean albumAllowed,
    boolean allowCopyToMain,
    boolean deletionAllowed,
    boolean deleted,
    // Block/unblock-on-sync feature (SpaceEntity.syncLockedByDevice) - syncLocked is true while
    // this Space is actively mirrored to an on-device folder, syncLockedByDeviceName names the
    // device for display ("Locked by: Alex's Pixel"), null when not locked.
    boolean syncLocked,
    String syncLockedByDeviceName
) {}
