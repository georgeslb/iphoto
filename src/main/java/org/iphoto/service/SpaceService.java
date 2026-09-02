package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.BaseEntity;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.SpaceType;
import org.iphoto.entities.UserEntity;
import org.iphoto.exception.MainSpaceDeletionConfigurationException;
import org.iphoto.exception.NoMatchUserSpaceException;
import org.iphoto.exception.NoPhotoInSpace;
import org.iphoto.exception.SpaceLockingException;
import org.iphoto.model.SpaceDTO;
import org.iphoto.model.request.SpaceCreateRequest;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.SpaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class SpaceService {

    private final SpaceRepository spaceRepository;
    private final PhotoRepository photoRepository;

    Map<UUID, AutoClosableLock> locks = new ConcurrentHashMap<>();


    public SpaceEntity getDefaultSpaceForUser(UserEntity user) {
        return spaceRepository.findByTypeEqualsMainAndUser(user);
    }

    public Optional<SpaceEntity> getSpaceById(UUID spaceId) {
        return spaceRepository.findById(spaceId);
    }

    public Optional<SpaceDTO> getSpaceByIdForUser(UUID spaceId, UserEntity user) {
        return spaceRepository.findByIdAndUser(spaceId, user).map(this::mapSpaceEntityToSpaceDto);
    }

    public SpaceEntity createDefaultSpace(UserEntity user) {
        SpaceEntity space = SpaceEntity.builder().name("MAIN").type(SpaceType.MAIN).isNew(true).isGarbage(false).user(user).photoCount(0).build();
        return spaceRepository.save(space);
    }

    public SpaceDTO createSpace(UserEntity user, SpaceCreateRequest spaceCreateRequest) {
        SpaceEntity space = SpaceEntity.builder().name(spaceCreateRequest.name()).isNew(true).isGarbage(false).user(user).photoCount(0).build();
        return mapSpaceEntityToSpaceDto(spaceRepository.save(space));
    }

    private SpaceDTO mapSpaceEntityToSpaceDto(SpaceEntity space) {
        boolean albumAllowed = space.getType() == SpaceType.MAIN;
        boolean allowCopyToMain = !albumAllowed; // Photos from non-main spaces can be copied to main
        return new SpaceDTO(space.getId(), space.getName(), space.getPhotoCount(), space.getCreatedAt(), space.getUpdatedAt(), albumAllowed, allowCopyToMain, allowCopyToMain, space.getIsGarbage().equals(true));
    }


    public List<SpaceDTO> getAllSpaces(UserEntity user) {
        return spaceRepository.findByUser(user).stream().map(this::mapSpaceEntityToSpaceDto).toList();
    }



    public void markSpaceForDeletion(UserEntity currentUser, UUID spaceId) {
        SpaceEntity space = spaceRepository.findByIdAndUser(spaceId, currentUser).orElseThrow(NoMatchUserSpaceException::new);
        if (space.getType() == SpaceType.MAIN) {
            throw new MainSpaceDeletionConfigurationException();
        }
        space.setIsGarbage(true);
        spaceRepository.save(space);
    }

    public AutoClosableLock lockSpace (SpaceEntity space) {
        locks.putIfAbsent(space.getId(), new AutoClosableLock());
        if (!locks.get(space.getId()).tryLock()) {
            log.debug("space is locked");
            throw new SpaceLockingException("lock space " + space.getId() +  " not possible");
        }
        return locks.get(space.getId());
    }

    public SpaceDTO getPhotoSpace(UserEntity currentUser, UUID id) {
        return photoRepository.findById(id)
                .filter(photoEntity -> photoEntity.getSpace().getUser().getId().equals(currentUser.getId()))
                .map(BaseEntity::getSpace)
                .map(this::mapSpaceEntityToSpaceDto)
                .orElseThrow(NoPhotoInSpace::new);
    }

    public void increaseSpacePhotoCount(SpaceEntity space) {
        increaseSpacePhotoCount(space, 1);
    }

    public void decreaseSpacePhotoCount(SpaceEntity space) {
        increaseSpacePhotoCount(space, -1);
    }

    public void increaseSpacePhotoCount(SpaceEntity space, int delta) {
            log.debug ("space {} photo count {} before", space.getName(), space.getPhotoCount());
            space.setPhotoCount(space.getPhotoCount() + delta);
            log.debug ("space {} photo count {} after", space.getName(), space.getPhotoCount());
            spaceRepository.save(space);
    }

    public List<SpaceDTO> getGarbageSpaces(UserEntity currentUser) {
        return spaceRepository.findByUser(currentUser)
                .stream().filter(SpaceEntity::getIsGarbage)
                .map(this::mapSpaceEntityToSpaceDto).toList();

    }

    public void restoreSpacesFromGarbage(UserEntity currentUser, List<UUID> ids) {
        spaceRepository.findByUser(currentUser)
                .stream().filter(space -> ids.contains(space.getId()))
                .forEach(space -> {space.setIsGarbage(false); spaceRepository.save(space);});
    }

    public void deleteSpace(UUID spaceId) {
        spaceRepository.deleteById(spaceId);
    }

    public static class AutoClosableLock extends ReentrantLock implements AutoCloseable {
        @Override
        public void close() {
            this.unlock();
        }
    }
}