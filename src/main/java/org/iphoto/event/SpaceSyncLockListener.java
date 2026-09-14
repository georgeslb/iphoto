package org.iphoto.event;

import lombok.RequiredArgsConstructor;
import org.iphoto.repository.SpaceRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Applies DeviceTokenRevokedEvent - the "I lost my phone" escape hatch for a sync lock: revoking
// the device from Manage Devices also frees any Space it had locked, same as stopping sync from
// the app would. Mirrors SpacePhotoCountListener's own shape (plain @EventListener, not
// @TransactionalEventListener, so this runs synchronously in the SAME transaction as the revoke
// itself - both commit or roll back together).
@Component
@RequiredArgsConstructor
public class SpaceSyncLockListener {

    private final SpaceRepository spaceRepository;

    @EventListener
    @Transactional
    public void onDeviceTokenRevoked(DeviceTokenRevokedEvent event) {
        spaceRepository.clearSyncLockForDevice(event.deviceTokenId());
    }
}
