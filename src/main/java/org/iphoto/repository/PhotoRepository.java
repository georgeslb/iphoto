package org.iphoto.repository;

import org.iphoto.entities.AlbumEntity;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.UserEntity; // Import UserEntity
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PhotoRepository extends JpaRepository<PhotoEntity, UUID> {
    
    Optional<PhotoEntity> findByUserAndFileHash(UserEntity user, String fileHash);
    
    Page<PhotoEntity> findByUserAndIsGarbageFalse(UserEntity user, Pageable pageable);
    
    Page<PhotoEntity> findByUserAndIsGarbageTrue(UserEntity user, Pageable pageable);

    Page<PhotoEntity> findByUserAndIsGarbageFalseAndAlbums(UserEntity user, AlbumEntity album, Pageable pageable);

    Page<PhotoEntity> findByUserAndFacesPerson(UserEntity user, PersonEntity person, Pageable pageable);

    long countByUserAndIsGarbageFalseAndAlbums(UserEntity user, AlbumEntity album);

    Slice<PhotoEntity> findByUserAndDateTakenBetweenAndPhashNotNullAndMhashNotNullAndIsGarbageFalse(UserEntity user, LocalDateTime startDateTaken, LocalDateTime endDateTaken, Pageable pageable);

    // Add a method to find a photo by ID and user
    Optional<PhotoEntity> findByIdAndUser(UUID id, UserEntity user);

    // Add method to find photos by user for AdminService
    Slice<PhotoEntity> findByUser(UserEntity user, Pageable pageable);
}