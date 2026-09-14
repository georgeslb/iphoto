package org.iphoto.event;

import java.util.UUID;

// Published by DeviceTokenServiceImpl#revoke - see SpaceSyncLockListener, which clears any Space
// still locked to that device token (a revoked device is the "I lost my phone" escape hatch for a
// stuck sync lock). Deliberately just the id, not the DeviceTokenEntity itself, mirroring
// SpacePhotoCountChangedEvent's own reasoning: publishing this should never require a managed
// entity instance, and it decouples the two services from depending on each other directly
// (DeviceTokenServiceImpl -> SpaceService would be circular with SpaceServiceImpl's own need to
// resolve DeviceTokenEntity rows when locking/unlocking).
public record DeviceTokenRevokedEvent(UUID deviceTokenId) {
}
