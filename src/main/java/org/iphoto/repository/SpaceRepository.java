package org.iphoto.repository;

import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.SpaceType;
import org.iphoto.entities.UserEntity;
import org.iphoto.exception.NoMatchUserSpaceException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface SpaceRepository extends JpaRepository<SpaceEntity, UUID> {
    Set<SpaceEntity> findByUser(UserEntity user);

    Optional<SpaceEntity> findByIdAndUser(UUID id, UserEntity user);

    // Pre-save collision check for SpaceService#createSpace - mirrors AlbumService.createAlbum/
    // ShareService.createShare's own findByName check, so a duplicate name reports 409 Conflict
    // instead of falling through to the name_user_unique constraint and 500ing.
    Optional<SpaceEntity> findByNameAndUser(String name, UserEntity user);

    default SpaceEntity findByTypeEqualsMainAndUser(UserEntity user) {
        return user.getSpaces().stream().filter(spaceEntity -> SpaceType.MAIN.equals(spaceEntity.getType())).findFirst().orElseThrow(NoMatchUserSpaceException::new);
    }

    // Backs SpacePhotoCountListener - a single atomic UPDATE instead of a Java-side
    // read/increment/save, so concurrent adjustments to the same space can never lose an update
    // (Postgres serializes concurrent writers to the same row for a plain UPDATE like this one -
    // no compare-and-swap/retry loop needed on top of it).
    @Modifying
    @Query("UPDATE SpaceEntity s SET s.photoCount = s.photoCount + :delta WHERE s.id = :spaceId")
    void adjustPhotoCount(@Param("spaceId") UUID spaceId, @Param("delta") int delta);

    // Applies DeviceTokenRevokedEvent (see SpaceSyncLockListener) - a revoked device token can't
    // own a sync lock any more, whether or not the user remembered to "stop sync" from the app
    // first. clearAutomatically for the same reason as every other bulk @Modifying query in this
    // codebase (e.g. TaskRepository#resetAllToPendingByTaskType) - a persistence-context copy of
    // the space loaded earlier in the same transaction would otherwise still show the stale lock.
    //
    // flushAutomatically is required here specifically (not just belt-and-braces): this runs
    // synchronously, in the SAME transaction as DeviceTokenServiceImpl#revoke's own
    // deviceTokenRepository.save(token) a few lines above it - that save's UPDATE is still only
    // pending (not yet flushed to the DB) when this bulk query fires. Without flushing first,
    // clearAutomatically's entityManager.clear() would silently DISCARD that pending change before
    // it's ever written - the transaction would still commit "successfully", but the device's
    // revokedAt would never actually persist. Confirmed live: without this flag,
    // DeviceTokenServiceTest#listForUserAndRevokeWorkThroughTheDevicesEndpoints failed with the
    // device coming back not-revoked after a 200 OK revoke call.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SpaceEntity s SET s.syncLockedByDevice = null WHERE s.syncLockedByDevice.id = :deviceTokenId")
    void clearSyncLockForDevice(@Param("deviceTokenId") UUID deviceTokenId);
}
