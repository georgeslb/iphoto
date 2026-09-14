package org.iphoto.exception;

import java.util.UUID;

// Thrown by SpaceService#assertSyncWritable when a write targets a Space currently locked to a
// device-folder sync (SpaceEntity.syncLockedByDevice) and the caller isn't authenticated as that
// exact device. Mapped to 423 LOCKED by GlobalExceptionHandler.
public class SpaceSyncLockedException extends RuntimeException {

    private final UUID spaceId;
    private final String lockedByDeviceName;

    public SpaceSyncLockedException(UUID spaceId, String lockedByDeviceName) {
        super("space " + spaceId + " is locked for sync by device '" + lockedByDeviceName + "'");
        this.spaceId = spaceId;
        this.lockedByDeviceName = lockedByDeviceName;
    }

    public UUID getSpaceId() {
        return spaceId;
    }

    public String getLockedByDeviceName() {
        return lockedByDeviceName;
    }
}
