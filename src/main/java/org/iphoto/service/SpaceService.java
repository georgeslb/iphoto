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
}
