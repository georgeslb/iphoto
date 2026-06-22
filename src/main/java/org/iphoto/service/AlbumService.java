package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.mappers.PhotoMapper;
import org.iphoto.model.AlbumCreateRequest;
import org.iphoto.model.AlbumDTO;
import org.iphoto.entities.AlbumEntity;
import org.iphoto.model.AlbumUpdateRequest;
import org.iphoto.model.Photo;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.UserEntity; // Import UserEntity
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
    private final PhotoMapper photoMapper; // To convert PhotoEntity to Photo DTO

    @Transactional
    public AlbumDTO createAlbum(UserEntity user, AlbumCreateRequest request) {
        if (albumRepository.findByUserAndName(user, request.name()).isPresent()) {
            throw new IllegalArgumentException("Album with name '" + request.name() + "' already exists.");
        }

        AlbumEntity album = new AlbumEntity();
        album.setUser(user); // Set the user for the album
        album.setName(request.name());
        album.setDescription(request.description());
        album.setPhotoCount(0);

        if (request.coverPhotoId() != null) {
            PhotoEntity coverPhoto = photoRepository.findByIdAndUser(request.coverPhotoId(), user)
                    .orElseThrow(() -> new NoSuchElementException("Cover photo not found with ID: " + request.coverPhotoId()));
            album.setCoverPhoto(coverPhoto);
        }

        return updateAlbumAndGetDto(user, album);
    }

    @Transactional(readOnly = true)
    public List<AlbumDTO> getAllAlbums(UserEntity user) {
        return albumRepository.findAllByUser(user)
                .stream()
                .sorted(Comparator.comparing(AlbumEntity::getName))
                .map(album -> updateAlbumAndGetDto(user, album)) // Pass user to updateAlbumAndGetDto
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AlbumDTO getAlbumById(UserEntity user, UUID id) {
        return albumRepository.findByIdAndUser(id, user)
                .map(album -> updateAlbumAndGetDto(user, album)) // Pass user to updateAlbumAndGetDto
                .orElseThrow(() -> new NoSuchElementException("Album not found with ID: " + id));
    }


    @Transactional
    public AlbumDTO updateAlbum(UserEntity user, UUID id, AlbumUpdateRequest request) {
        AlbumEntity album = getAlbum(user, id);

        if (request.name() != null && !request.name().equals(album.getName())) {
            if (albumRepository.findByUserAndName(user, request.name()).isPresent()) {
                throw new IllegalArgumentException("Album with name '" + request.name() + "' already exists.");
            }
            album.setName(request.name());
        }
        if (request.description() != null) {
            album.setDescription(request.description());
        }
        if (request.coverPhotoId() != null) {
            PhotoEntity coverPhoto = photoRepository.findByIdAndUser(request.coverPhotoId(), user)
                    .orElseThrow(() -> new NoSuchElementException("Cover photo not found with ID: " + request.coverPhotoId()));
            album.setCoverPhoto(coverPhoto);
        }

        return updateAlbumAndGetDto(user, album);
    }

    @Transactional
    public void deleteAlbum(UserEntity user, UUID id) {
        AlbumEntity album = getAlbum(user, id); // Ensure user owns the album
        albumRepository.delete(album);
    }

    @Transactional
    public AlbumDTO addPhotosToAlbum(UserEntity user, UUID albumId, List<UUID> photoIds) {
        AlbumEntity album = getAlbum(user, albumId);

        Set<PhotoEntity> photosToAdd = photoIds.stream()
                .map(id -> photoRepository.findByIdAndUser(id, user)) // Filter photos by user
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toSet());

        album.getPhotos().addAll(photosToAdd);
        return updateAlbumAndGetDto(user, album);
    }

    @Transactional
    public AlbumDTO removePhotosFromAlbum(UserEntity user, UUID albumId, List<UUID> photoIds) {
        final AlbumEntity album = getAlbum(user, albumId); // Ensure user owns the album

        List<PhotoEntity> selectedPhotos = photoIds.stream()
                .map(id -> photoRepository.findByIdAndUser(id, user)) // Filter photos by user
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        selectedPhotos.forEach(album.getPhotos()::remove);
        if (album.getCoverPhoto() != null && photoIds.stream().anyMatch(photoId -> photoId.equals(album.getCoverPhoto().getId()))) {
            album.setCoverPhoto(null);
        }
        return updateAlbumAndGetDto(user, album);
    }


    private AlbumEntity getAlbum(UserEntity user, UUID albumId) {
        return albumRepository.findByIdAndUser(albumId, user)
                .orElseThrow(() -> new NoSuchElementException("Album not found with ID: " + albumId));
    }

    @Transactional(readOnly = true)
    public List<Photo> getAlbumPhotos(UserEntity user, UUID albumId, Pageable pageable) {
        AlbumEntity album = getAlbum(user, albumId);
        return photoRepository.findByUserAndIsGarbageFalseAndAlbums(user, album, pageable).stream()
                .map(photoMapper::photoEntityToPhoto)
                .collect(Collectors.toList());
    }

    public AlbumDTO convertToDto(UserEntity user, AlbumEntity album) { // Added UserEntity parameter
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

    public AlbumEntity updateAlbum(UserEntity user, AlbumEntity albumEntity) { // Added UserEntity parameter
        if (albumEntity.getId() != null) {
            if (albumEntity.getCoverPhoto() == null ||
                    photoRepository.findByIdAndUser(albumEntity.getCoverPhoto().getId(), user).isEmpty() || // Check user ownership
                    photoRepository.findByIdAndUser(albumEntity.getCoverPhoto().getId(), user).get().getIsGarbage() || // Check user ownership
                    !albumRepository.existsByUserAndIdAndPhotos_Id(user, albumEntity.getId(),albumEntity.getCoverPhoto().getId())) { // Check user ownership
                selectANewPhotoAsCover(user, albumEntity); // Pass user
            }

            setAlbumPhotoCount(user, albumEntity); // Pass user
        }
        return albumRepository.save(albumEntity);
    }


    private void selectANewPhotoAsCover(UserEntity user, AlbumEntity album) { // Added UserEntity parameter
        long photoInAlbumCount = photoRepository.countByUserAndIsGarbageFalseAndAlbums(user, album); // Filter by user
        if (photoInAlbumCount > 0) {
            Pageable pageable = PageRequest.of(0, 50);
            photoRepository.findByUserAndIsGarbageFalseAndAlbums(user, album, pageable) // Filter by user
                    .stream().filter(photoEntity -> photoEntity.getContentType().startsWith("image"))
                    .skip(ThreadLocalRandom.current().nextInt((int) Math.min(photoInAlbumCount, 50)))
                    .findAny().ifPresent(album::setCoverPhoto);
        }
    }

    private AlbumDTO updateAlbumAndGetDto(UserEntity user, AlbumEntity album) { // Added UserEntity parameter
        return convertToDto(user, updateAlbum(user, album)); // Pass user
    }

    private void setAlbumPhotoCount(UserEntity user, AlbumEntity albumEntity) { // Added UserEntity parameter
        long photoInAlbum = photoRepository.countByUserAndIsGarbageFalseAndAlbums(user, albumEntity); // Filter by user
        albumEntity.setPhotoCount((int) photoInAlbum);
    }
}