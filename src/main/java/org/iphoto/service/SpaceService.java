package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.*;
import org.iphoto.exception.MainSpaceDeletionConfigurationException;
import org.iphoto.exception.NoMatchUserSpaceException;
import org.iphoto.exception.NoPhotoInSpace;
import org.iphoto.model.Photo;
import org.iphoto.model.SpaceDTO;
import org.iphoto.model.request.SpaceCreateRequest;
import org.iphoto.repository.PhotoRepository;
import org.iphoto.repository.SpaceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SpaceService {

    private final SpaceRepository spaceRepository;
    private final PhotoService photoService;
    private final PhotoRepository photoRepository;


    @Transactional
    public SpaceEntity getDefaultSpaceForUser(UserEntity user) {
        return spaceRepository.findByTypeEqualsMainAndUser(user);
    }

    @Transactional
    public Optional<SpaceEntity> getSpaceByIdForUser(UUID spaceId, UserEntity user) {
        return spaceRepository.findByIdAndUser(spaceId, user);
    }

    public SpaceEntity createDefaultSpace(UserEntity user) {
        var space = SpaceEntity.builder().name("MAIN").type(SpaceType.MAIN).isNew(true).user(user).photoCount(0).build();
        return spaceRepository.save(space);
    }

    public SpaceDTO createSpace(UserEntity user, SpaceCreateRequest spaceCreateRequest) {
        SpaceEntity space = SpaceEntity.builder().name(spaceCreateRequest.name()).isNew(true).user(user).photoCount(0).build();
        return mapSpaceEntityToSpaceDto(spaceRepository.save(space));
    }

    private SpaceDTO mapSpaceEntityToSpaceDto(SpaceEntity space) {
        boolean albumAllowed = space.getType() == SpaceType.MAIN;
        boolean allowCopyToMain = !albumAllowed; // Photos from non-main spaces can be copied to main
        return new SpaceDTO(space.getId(), space.getName(), space.getPhotoCount(), space.getCreatedAt(), space.getUpdatedAt(), albumAllowed, allowCopyToMain);
    }


    public List<SpaceDTO> getAllSpaces(UserEntity user) {
        return spaceRepository.findByUser(user).stream().map(this::mapSpaceEntityToSpaceDto).toList();
    }

    public Page<Photo> getSpacePhotos(UserEntity currentUser, UUID spaceId, Pageable pageable) {
        SpaceEntity space = spaceRepository.findByIdAndUser(spaceId, currentUser).orElseThrow(NoMatchUserSpaceException::new);
        return photoService.getPhotos(space, pageable);
    }

    public void deleteSpace(UserEntity currentUser, UUID spaceId) {
        SpaceEntity space = spaceRepository.findByIdAndUser(spaceId, currentUser).orElseThrow(NoMatchUserSpaceException::new);
        if (space.getType() == SpaceType.MAIN) {
            throw new MainSpaceDeletionConfigurationException();
        }
        Pageable pageable = PageRequest.of(0, 100);
        Slice<PhotoEntity> photosSlice;
        do {
            // Filter photos by user
            photosSlice = photoRepository.findAll(pageable); // Now findByUser exists in PhotoRepository
            List<UUID> listToMoveToGarbage = photosSlice.getContent().stream().map(PhotoEntity::getId).toList();
            photoService.moveAssetsToGarbage(currentUser, listToMoveToGarbage);
            pageable = photosSlice.nextPageable();

        } while (photosSlice.hasNext());
        spaceRepository.delete(space);
    }

    public SpaceDTO getPhotoSpace(UserEntity currentUser, UUID id) {
        return photoRepository.findById(id)
                .filter(photoEntity -> photoEntity.getSpace().getUser().getId().equals(currentUser.getId()))
                .map(BaseEntity::getSpace)
                .map(this::mapSpaceEntityToSpaceDto)
                .orElseThrow(NoPhotoInSpace::new);
    }
}