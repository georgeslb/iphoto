package org.iphoto.service;

import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.model.SpaceDTO;
import org.iphoto.model.request.SpaceCreateRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpaceService {

    SpaceEntity getDefaultSpaceForUser(UserEntity user);

    Optional<SpaceEntity> getSpaceById(UUID spaceId);

    Optional<SpaceDTO> getSpaceByIdForUser(UUID spaceId, UserEntity user);

    SpaceEntity createDefaultSpace(UserEntity user);

    SpaceDTO createSpace(UserEntity user, SpaceCreateRequest spaceCreateRequest);

    List<SpaceDTO> getAllSpaces(UserEntity user);

    void markSpaceForDeletion(UserEntity currentUser, UUID spaceId);

    SpaceDTO getPhotoSpace(UserEntity currentUser, UUID id);

    void increaseSpacePhotoCount(SpaceEntity space);

    void decreaseSpacePhotoCount(SpaceEntity space);

    // Publishes SpacePhotoCountChangedEvent instead of a Java-side read/increment/save - see
    // SpacePhotoCountListener for the atomic UPDATE this triggers, and why that alone (no lock,
    // no compare-and-swap retry loop) is enough. Fires synchronously, in the caller's own
    // transaction (a plain @EventListener, not @TransactionalEventListener) so the count change
    // commits or rolls back together with whatever photo add/remove triggered it.
    void increaseSpacePhotoCount(SpaceEntity space, int delta);

    List<SpaceDTO> getGarbageSpaces(UserEntity currentUser);

    void restoreSpacesFromGarbage(UserEntity currentUser, List<UUID> ids);

    void deleteSpace(UUID spaceId);

    // Block/unblock-on-sync feature (SpaceEntity.syncLockedByDevice). callerDeviceTokenId must be
    // non-null (only the Android app, authenticated as a specific device, can start a lock) -
    // SpaceController rejects a web-session caller before this is even reached. Idempotent for the
    // same device; throws SpaceSyncLockedException if already locked by a DIFFERENT device.
    SpaceDTO lockForSync(UUID spaceId, UserEntity user, UUID callerDeviceTokenId);

    // callerDeviceTokenId null means a web-session caller - the "lost my phone" escape hatch, so
    // the space's own owner can always clear a stuck lock from the browser, not just the device
    // that set it. A non-null id must match the lock's own device, or this throws
    // SpaceSyncLockedException the same way a blocked write would. Unlocking an already-unlocked
    // space is a harmless no-op either way.
    SpaceDTO unlockFromSync(UUID spaceId, UserEntity user, UUID callerDeviceTokenId);

    // Throws SpaceSyncLockedException if `space` is currently locked to a device-folder sync and
    // the current request isn't authenticated as that exact device (DeviceAuthContext). No-op when
    // not locked. Call this from any service write that changes a space's photo content, right
    // where the SpaceEntity is already in hand, before applying the mutation.
    void assertSyncWritable(SpaceEntity space);
}
