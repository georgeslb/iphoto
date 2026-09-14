package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.configuration.DeviceAuthContext;
import org.iphoto.entities.BaseEntity;
import org.iphoto.entities.DeviceTokenEntity;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.SpaceType;
import org.iphoto.entities.UserEntity;
import org.iphoto.event.SpacePhotoCountChangedEvent;
import org.iphoto.exception.MainSpaceDeletionConfigurationException;
import org.iphoto.exception.NoMatchUserSpaceException;
import org.iphoto.exception.NoPhotoInSpace;
import org.iphoto.exception.SpaceSyncLockedException;
import org.iphoto.model.SpaceDTO;
import org.iphoto.model.request.SpaceCreateRequest;
import org.iphoto.repository.DeviceTokenRepository;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.SpaceRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class SpaceServiceImpl implements SpaceService {

    private final SpaceRepository spaceRepository;
    private final PhotoRepository photoRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final ApplicationEventPublisher eventPublisher;


    @Override
    public SpaceEntity getDefaultSpaceForUser(UserEntity user) {
        return spaceRepository.findByTypeEqualsMainAndUser(user);
    }

    @Override
    public Optional<SpaceEntity> getSpaceById(UUID spaceId) {
        return spaceRepository.findById(spaceId);
    }

    @Override
    public Optional<SpaceDTO> getSpaceByIdForUser(UUID spaceId, UserEntity user) {
        return spaceRepository.findByIdAndUser(spaceId, user).map(this::mapSpaceEntityToSpaceDto);
    }

    @Override
    public SpaceEntity createDefaultSpace(UserEntity user) {
        SpaceEntity space = SpaceEntity.builder().name("MAIN").type(SpaceType.MAIN).isNew(true).isGarbage(false).user(user).photoCount(0).build();
        return spaceRepository.save(space);
    }

    @Override
    public SpaceDTO createSpace(UserEntity user, SpaceCreateRequest spaceCreateRequest) {
        if (spaceRepository.findByNameAndUser(spaceCreateRequest.name(), user).isPresent()) {
            throw new IllegalArgumentException("Space with name '" + spaceCreateRequest.name() + "' already exists.");
        }
        SpaceEntity space = SpaceEntity.builder().name(spaceCreateRequest.name()).isNew(true).isGarbage(false).user(user).photoCount(0).build();
        return mapSpaceEntityToSpaceDto(spaceRepository.save(space));
    }

    private SpaceDTO mapSpaceEntityToSpaceDto(SpaceEntity space) {
        boolean albumAllowed = space.getType() == SpaceType.MAIN;
        boolean allowCopyToMain = !albumAllowed; // Photos from non-main spaces can be copied to main
        DeviceTokenEntity syncLock = space.getSyncLockedByDevice();
        return new SpaceDTO(space.getId(), space.getName(), space.getPhotoCount(), space.getCreatedAt(), space.getUpdatedAt(),
                albumAllowed, allowCopyToMain, allowCopyToMain, space.getIsGarbage().equals(true),
                syncLock != null, syncLock != null ? syncLock.getDeviceName() : null);
    }


    @Override
    public List<SpaceDTO> getAllSpaces(UserEntity user) {
        return spaceRepository.findByUser(user).stream().map(this::mapSpaceEntityToSpaceDto).toList();
    }



    @Override
    public void markSpaceForDeletion(UserEntity currentUser, UUID spaceId) {
        SpaceEntity space = spaceRepository.findByIdAndUser(spaceId, currentUser).orElseThrow(NoMatchUserSpaceException::new);
        if (space.getType() == SpaceType.MAIN) {
            throw new MainSpaceDeletionConfigurationException();
        }
        assertSyncWritable(space);
        space.setIsGarbage(true);
        spaceRepository.save(space);
    }

    @Override
    public SpaceDTO getPhotoSpace(UserEntity currentUser, UUID id) {
        return photoRepository.findById(id)
                .filter(photoEntity -> photoEntity.getSpace().getUser().getId().equals(currentUser.getId()))
                .map(BaseEntity::getSpace)
                .map(this::mapSpaceEntityToSpaceDto)
                .orElseThrow(NoPhotoInSpace::new);
    }

    @Override
    public void increaseSpacePhotoCount(SpaceEntity space) {
        increaseSpacePhotoCount(space, 1);
    }

    @Override
    public void decreaseSpacePhotoCount(SpaceEntity space) {
        increaseSpacePhotoCount(space, -1);
    }

    @Override
    public void increaseSpacePhotoCount(SpaceEntity space, int delta) {
        eventPublisher.publishEvent(new SpacePhotoCountChangedEvent(space.getId(), delta));
    }

    @Override
    public List<SpaceDTO> getGarbageSpaces(UserEntity currentUser) {
        return spaceRepository.findByUser(currentUser)
                .stream().filter(SpaceEntity::getIsGarbage)
                .map(this::mapSpaceEntityToSpaceDto).toList();

    }

    @Override
    public void restoreSpacesFromGarbage(UserEntity currentUser, List<UUID> ids) {
        spaceRepository.findByUser(currentUser)
                .stream().filter(space -> ids.contains(space.getId()))
                .forEach(space -> {space.setIsGarbage(false); spaceRepository.save(space);});
    }

    @Override
    public void deleteSpace(UUID spaceId) {
        spaceRepository.deleteById(spaceId);
    }

    @Override
    public SpaceDTO lockForSync(UUID spaceId, UserEntity user, UUID callerDeviceTokenId) {
        if (callerDeviceTokenId == null) {
            // Defensive - SpaceController already rejects a non-device-authenticated caller
            // before this is reached, so this should be unreachable in practice.
            throw new IllegalArgumentException("Only the mobile app can lock a space for sync");
        }
        SpaceEntity space = spaceRepository.findByIdAndUser(spaceId, user).orElseThrow(NoMatchUserSpaceException::new);
        if (space.getType() == SpaceType.MAIN) {
            // MAIN holds every album's referenced photos and can't itself be trashed/deleted - the
            // same "not a normal mirror target" reasoning extends to sync locking.
            throw new IllegalArgumentException("The MAIN space cannot be locked for sync");
        }

        DeviceTokenEntity currentLock = space.getSyncLockedByDevice();
        if (currentLock != null && !currentLock.getId().equals(callerDeviceTokenId)) {
            throw new SpaceSyncLockedException(space.getId(), currentLock.getDeviceName());
        }
        if (currentLock == null) {
            DeviceTokenEntity deviceToken = deviceTokenRepository.findById(callerDeviceTokenId)
                    .orElseThrow(() -> new NoSuchElementException("no device token found with id " + callerDeviceTokenId));
            space.setSyncLockedByDevice(deviceToken);
            spaceRepository.save(space);
        }
        // Already locked by this same device - idempotent no-op, matches "sync is active" being a
        // persistent state the app can safely re-assert on every sync run, not a one-shot toggle.
        return mapSpaceEntityToSpaceDto(space);
    }

    @Override
    public SpaceDTO unlockFromSync(UUID spaceId, UserEntity user, UUID callerDeviceTokenId) {
        SpaceEntity space = spaceRepository.findByIdAndUser(spaceId, user).orElseThrow(NoMatchUserSpaceException::new);
        DeviceTokenEntity currentLock = space.getSyncLockedByDevice();
        if (currentLock != null) {
            boolean isOwningDevice = callerDeviceTokenId != null && currentLock.getId().equals(callerDeviceTokenId);
            boolean isWebSessionEscapeHatch = callerDeviceTokenId == null;
            if (!isOwningDevice && !isWebSessionEscapeHatch) {
                throw new SpaceSyncLockedException(space.getId(), currentLock.getDeviceName());
            }
            space.setSyncLockedByDevice(null);
            spaceRepository.save(space);
        }
        return mapSpaceEntityToSpaceDto(space);
    }

    @Override
    public void assertSyncWritable(SpaceEntity space) {
        DeviceTokenEntity lockedBy = space.getSyncLockedByDevice();
        if (lockedBy == null) {
            return;
        }
        boolean callerIsOwningDevice = DeviceAuthContext.currentDeviceTokenId()
                .map(id -> id.equals(lockedBy.getId()))
                .orElse(false);
        if (!callerIsOwningDevice) {
            throw new SpaceSyncLockedException(space.getId(), lockedBy.getDeviceName());
        }
    }
}
