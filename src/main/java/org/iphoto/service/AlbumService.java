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
    public AlbumDTO createAlbum(AlbumCreateRequest request) {
        if (albumRepository.findByName(request.name()).isPresent()) {
            throw new IllegalArgumentException("Album with name '" + request.name() + "' already exists.");
        }

        AlbumEntity album = new AlbumEntity();
        album.setName(request.name());
        album.setDescription(request.description());
        album.setPhotoCount(0);

        if (request.coverPhotoId() != null) {
            PhotoEntity coverPhoto = photoRepository.findById(request.coverPhotoId())
                    .orElseThrow(() -> new NoSuchElementException("Cover photo not found with ID: " + request.coverPhotoId()));
            album.setCoverPhoto(coverPhoto);
        }

        return updateAlbumAndGetDto(album);
    }

    @Transactional(readOnly = true)
    public List<AlbumDTO> getAllAlbums() {
        return albumRepository.findAll().stream()
                .map(this::updateAlbumAndGetDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AlbumDTO getAlbumById(UUID id) {
        return albumRepository.findById(id)
                .map(this::updateAlbumAndGetDto)
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
        albumRepository.deleteById(id);
    }

    @Transactional
    public AlbumDTO addPhotosToAlbum(UUID albumId, List<UUID> photoIds) {
        AlbumEntity album = getAlbum(albumId);

        Set<PhotoEntity> photosToAdd = photoIds.stream()
                .map(photoRepository::findById)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .collect(Collectors.toSet());

        album.getPhotos().addAll(photosToAdd);
        return updateAlbumAndGetDto(album);
    }

    @Transactional
    public AlbumDTO removePhotosFromAlbum(UUID albumId, List<UUID> photoIds) {
        final AlbumEntity album = getAlbum(albumId);

        List<PhotoEntity> selectedPhotos = photoRepository.findAllById(photoIds);
        selectedPhotos.forEach(album.getPhotos()::remove);
        if (album.getCoverPhoto() != null && photoIds.stream().anyMatch(photoId -> photoId.equals(album.getCoverPhoto().getId()))) {
            album.setCoverPhoto(null);
        }
        return updateAlbumAndGetDto(album);
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

    private AlbumDTO convertToDto(AlbumEntity album) {
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

    public AlbumEntity updateAlbum(AlbumEntity albumEntity) {
        if (albumEntity.getId() != null) {
            if (albumEntity.getCoverPhoto() == null ||
                    photoRepository.findById(albumEntity.getCoverPhoto().getId()).isEmpty() ||
                    photoRepository.findById(albumEntity.getCoverPhoto().getId()).get().getIsGarbage() ||
                    !albumRepository.existsByIdAndPhotos_Id(albumEntity.getId(),albumEntity.getCoverPhoto().getId())) {
                selectANewPhotoAsCover(albumEntity);
            }

            setAlbumPhotoCount(albumEntity);
        }
        return albumRepository.save(albumEntity);
    }


    private void selectANewPhotoAsCover(AlbumEntity album) {
        long photoInAlbumCount = photoRepository.countByIsGarbageFalseAndAlbums(album);
        if (photoInAlbumCount > 0) {
            Pageable pageable = PageRequest.of(0, 50);
            photoRepository.findByIsGarbageFalseAndAlbums(album, pageable)
                    .stream().filter(photoEntity -> photoEntity.getContentType().startsWith("image"))
                    .skip(ThreadLocalRandom.current().nextInt((int) Math.min(photoInAlbumCount, 50)))
                    .findAny().ifPresent(album::setCoverPhoto);
        }
    }

    private AlbumDTO updateAlbumAndGetDto(AlbumEntity album) {
        return convertToDto(updateAlbum(album));
    }

    private void setAlbumPhotoCount(AlbumEntity albumEntity) {
        long photoInAlbum = photoRepository.countByIsGarbageFalseAndAlbums(albumEntity);
        albumEntity.setPhotoCount((int) photoInAlbum);
    }
}
