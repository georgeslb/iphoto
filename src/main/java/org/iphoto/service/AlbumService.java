package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.*;
import org.iphoto.exception.MainSpaceAddToAlbumException;
import org.iphoto.mappers.PhotoMapper;
import org.iphoto.model.AlbumDTO;
import org.iphoto.model.Photo;
import org.iphoto.model.request.AlbumCreateRequest;
import org.iphoto.model.request.AlbumUpdateRequest;
import org.iphoto.repository.AlbumRepository;
import org.iphoto.repository.PhotoRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        return updateAlbumAndGetDto(album);
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
        return updateAlbumAndGetDto(album);
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
    public List<Photo> getAlbumPhotos(UUID albumId, Pageable pageable) {
        AlbumEntity album = getAlbum(albumId);
        return photoRepository.findByIsGarbageFalseAndAlbums(album, pageable).stream()
                .map(photoMapper::photoEntityToPhoto)
                .collect(Collectors.toList());
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
        long photoInAlbumCount = photoRepository.countByIsGarbageFalseAndAlbums(album); // Filter by user
        if (photoInAlbumCount > 0) {
            Pageable pageable = PageRequest.of(0, 50);
            photoRepository.findByIsGarbageFalseAndAlbums(album, pageable) // Filter by user
                    .stream().filter(photoEntity -> photoEntity.getContentType().startsWith("image"))
                    .skip(ThreadLocalRandom.current().nextInt((int) Math.min(photoInAlbumCount, 50)))
                    .findAny().ifPresent(album::setCoverPhoto);
        }
    }

    private AlbumDTO updateAlbumAndGetDto(AlbumEntity album) { // Added UserEntity parameter
        return convertToDto(updateAlbum(album)); // Pass user
    }

    private void setAlbumPhotoCount(AlbumEntity albumEntity) { // Added UserEntity parameter
        long photoInAlbum = photoRepository.countByIsGarbageFalseAndAlbums(albumEntity); // Filter by user
        albumEntity.setPhotoCount((int) photoInAlbum);
    }
}