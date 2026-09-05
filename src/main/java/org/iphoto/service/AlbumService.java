package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.*;
import org.iphoto.exception.MainSpaceAddToAlbumException;
import org.iphoto.mappers.PhotoMapper;
import org.iphoto.model.AlbumDTO;
import org.iphoto.model.DayBucketDTO;
import org.iphoto.model.Photo;
import org.iphoto.model.request.AlbumCreateRequest;
import org.iphoto.model.request.AlbumUpdateRequest;
import org.iphoto.repository.AlbumRepository;
import org.iphoto.repository.PhotoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlbumService {

    private final AlbumRepository albumRepository;
    private final PhotoRepository photoRepository;
    private final PhotoMapper photoMapper;
    private final DayIndexCache dayIndexCache;


    @Transactional
    public AlbumDTO createAlbum(SpaceEntity space, AlbumCreateRequest request) {
        if (albumRepository.findByName(request.name()).isPresent()) {
            throw new IllegalArgumentException("Album with name '" + request.name() + "' already exists.");
        }

        AlbumEntity album = AlbumEntity.builder().space(space).name(request.name()).description(request.description()).photoCount(0).build();

        if (request.coverPhotoId() != null) {
            PhotoEntity coverPhoto = photoRepository.findById(request.coverPhotoId())
                    .orElseThrow(() -> new NoSuchElementException("Cover photo not found with ID: " + request.coverPhotoId()));
            album.setCoverPhoto(coverPhoto);
        }

        return updateAlbumAndGetDto(album);
    }

    @Transactional(readOnly = true)
    public List<AlbumDTO> getAllAlbums() {
        return albumRepository.findAll()
                .stream()
                .sorted(Comparator.comparing(AlbumEntity::getName))
                .map(this::updateAlbumAndGetDto) // Pass user to updateAlbumAndGetDto
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AlbumDTO getAlbumById(UUID id) {
        return albumRepository.findById(id)
                .map(this::updateAlbumAndGetDto) // Pass user to updateAlbumAndGetDto
                .orElseThrow(() -> new NoSuchElementException("Album not found with ID: " + id));
    }


    @Transactional
    public AlbumDTO updateAlbum(UUID id, AlbumUpdateRequest request) {
        AlbumEntity album = getAlbum(id);

        if (request.name() != null && !request.name().equals(album.getName())) {
            if (albumRepository.findByName(request.name()).isPresent()) {
                throw new IllegalArgumentException("Album with name '" + request.name() + "' already exists.");
            }
            album.setName(request.name());
        }
        if (request.description() != null) {
            album.setDescription(request.description());
        }
        if (request.coverPhotoId() != null) {
            PhotoEntity coverPhoto = photoRepository.findById(request.coverPhotoId())
                    .orElseThrow(() -> new NoSuchElementException("Cover photo not found with ID: " + request.coverPhotoId()));
            album.setCoverPhoto(coverPhoto);
        }

        return updateAlbumAndGetDto(album);
    }

    @Transactional
    public void deleteAlbum(UUID id) {
        AlbumEntity album = getAlbum(id);
        albumRepository.delete(album);
    }

    @Transactional
    public AlbumDTO addPhotosToAlbum(UUID albumId, List<UUID> photoIds)  {
        AlbumEntity album = getAlbum(albumId);
        Set<PhotoEntity> photosToAdd = photoRepository.findByIdIn(photoIds);
        checkPhotosAreInMainSpace(photosToAdd);
        album.getPhotos().addAll(photosToAdd);
        AlbumDTO dto = updateAlbumAndGetDto(album);
        dayIndexCache.evictAlbum(albumId);
        return dto;
    }



    @Transactional
    public AlbumDTO removePhotosFromAlbum(UUID albumId, List<UUID> photoIds) {
        final AlbumEntity album = getAlbum(albumId);

        Set<PhotoEntity> selectedPhotos = photoRepository.findByIdIn(photoIds);
        checkPhotosAreInMainSpace(selectedPhotos);
        selectedPhotos.forEach(album.getPhotos()::remove);
        if (album.getCoverPhoto() != null && photoIds.stream().anyMatch(photoId -> photoId.equals(album.getCoverPhoto().getId()))) {
            album.setCoverPhoto(null);
        }
        AlbumDTO dto = updateAlbumAndGetDto(album);
        dayIndexCache.evictAlbum(albumId);
        return dto;
    }

    private void checkPhotosAreInMainSpace(Set<PhotoEntity> photosToAdd) {
        if (photosToAdd.stream().map(BaseEntity::getSpace).distinct().map(SpaceEntity::getType).anyMatch(type -> !SpaceType.MAIN.equals(type))) {
            throw new MainSpaceAddToAlbumException();
        }
    }

    private AlbumEntity getAlbum(UUID albumId) {
        return albumRepository.findById(albumId)
                .orElseThrow(() -> new NoSuchElementException("Album not found with ID: " + albumId));
    }

    @Transactional(readOnly = true)
    public Page<Photo> getAlbumPhotos(UUID albumId, Pageable pageable) {
        AlbumEntity album = getAlbum(albumId);
        return photoRepository.findByIsGarbageFalseAndAlbums(Set.of(album), pageable)
                .map(photoMapper::photoEntityToPhoto);
    }

    // Day-index browsing for Album detail (see PhotoService.getDailyPhotoCounts, the Library/
    // Space equivalent) - an album references photos rather than owning them, so this groups
    // through the album_photos join instead of by space_id. No buildDedupedPage-style
    // duplicate-clustering here, matching getAlbumPhotos above (Album detail doesn't get that
    // treatment today either). Cached (see DayIndexCache), evicted on addPhotosToAlbum/
    // removePhotosFromAlbum above.
    @Transactional(readOnly = true)
    public List<DayBucketDTO> getAlbumDailyPhotoCounts(UUID albumId) {
        AlbumEntity album = getAlbum(albumId);
        return dayIndexCache.getAlbumDayCounts(albumId, () -> DayBucketDTO.from(photoRepository.countDailyPhotosByAlbums(Set.of(album))));
    }

    @Transactional(readOnly = true)
    public Page<Photo> getAlbumPhotosForDay(UUID albumId, LocalDate day, Pageable pageable) {
        AlbumEntity album = getAlbum(albumId);
        return photoRepository.findByIsGarbageFalseAndAlbumsAndGroupedDay(Set.of(album), day, pageable)
                .map(photoMapper::photoEntityToPhoto);
    }

    // Guest/share-flow variants: deliberately use the unfiltered album lookup since a guest
    // share session has no matching UserEntity (the space-filter aspect would throw trying to
    // resolve one). Album/photo-level authorization for guests is enforced separately, by
    // ShareAccessFilter checking the requested album against the share the guest authenticated
    // for — do not reuse these from any authenticated-user-facing endpoint.
    @Transactional(readOnly = true)
    public Page<Photo> getAlbumPhotosForShare(UUID albumId, Pageable pageable) {
        AlbumEntity album = getAlbumIgnoringSpace(albumId);
        return photoRepository.findByIsGarbageFalseAndAlbums(Set.of(album), pageable)
                .map(photoMapper::photoEntityToPhoto);
    }

    @Transactional(readOnly = true)
    public Photo getAlbumPhotoForShare(UUID albumId, UUID photoId) {
        getAlbumIgnoringSpace(albumId); // confirms the album itself exists
        return photoRepository.findByIdAndAlbums_IdAndIsGarbageFalse(photoId, albumId)
                .map(photoMapper::photoEntityToPhoto)
                .orElseThrow(() -> new NoSuchElementException("Photo not found in album: " + photoId));
    }

    private AlbumEntity getAlbumIgnoringSpace(UUID albumId) {
        return albumRepository.findByIdIgnoringSpace(albumId)
                .orElseThrow(() -> new NoSuchElementException("Album not found with ID: " + albumId));
    }

    public AlbumDTO convertToDto(AlbumEntity album) { // Added UserEntity parameter
        Photo coverPhotoDTO = null;
        if (album.getCoverPhoto() != null) {
            coverPhotoDTO = photoMapper.photoEntityToPhoto(album.getCoverPhoto());
        }

        return new AlbumDTO(
                album.getId(),
                album.getName(),
                album.getDescription(),
                coverPhotoDTO,
                album.getPhotoCount()  == null ? 0 : album.getPhotoCount(),
                album.getCreatedAt(),
                album.getUpdatedAt()
        );
    }

    public AlbumEntity updateAlbum(AlbumEntity albumEntity) { // Added UserEntity parameter
        if (albumEntity.getId() != null) {
            if (albumEntity.getCoverPhoto() == null ||
                    photoRepository.findById(albumEntity.getCoverPhoto().getId()).isEmpty() || // Check user ownership
                    photoRepository.findById(albumEntity.getCoverPhoto().getId()).get().getIsGarbage() || // Check user ownership
                    !albumRepository.existsByIdAndPhotos_Id(albumEntity.getId(),albumEntity.getCoverPhoto().getId())) { // Check user ownership
                selectANewPhotoAsCover(albumEntity); // Pass user
            }

            setAlbumPhotoCount(albumEntity); // Pass user
        }
        return albumRepository.save(albumEntity);
    }


    private void selectANewPhotoAsCover(AlbumEntity album) { // Added UserEntity parameter
        long photoInAlbumCount = photoRepository.countByIsGarbageFalseAndAlbums(Set.of(album)); // Filter by user
        if (photoInAlbumCount > 0) {
            Pageable pageable = PageRequest.of(0, 50);
            photoRepository.findByIsGarbageFalseAndAlbums(Set.of(album), pageable) // Filter by user
                    .stream().filter(photoEntity -> photoEntity.getContentType().startsWith("image"))
                    .skip(ThreadLocalRandom.current().nextInt((int) Math.min(photoInAlbumCount, 50)))
                    .findAny().ifPresent(album::setCoverPhoto);
        }
    }

    private AlbumDTO updateAlbumAndGetDto(AlbumEntity album) { // Added UserEntity parameter
        return convertToDto(updateAlbum(album)); // Pass user
    }

    private void setAlbumPhotoCount(AlbumEntity albumEntity) { // Added UserEntity parameter
        long photoInAlbum = photoRepository.countByIsGarbageFalseAndAlbums(Set.of(albumEntity)); // Filter by user
        albumEntity.setPhotoCount((int) photoInAlbum);
    }
}