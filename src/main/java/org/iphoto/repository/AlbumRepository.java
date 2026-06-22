package org.iphoto.repository;

import org.iphoto.entities.AlbumEntity;
import org.iphoto.entities.UserEntity; // Import UserEntity
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;


import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AlbumRepository extends JpaRepository<AlbumEntity, UUID> {
    Optional<AlbumEntity> findByUserAndName(UserEntity user, String name);

    boolean existsByUserAndIdAndPhotos_Id(UserEntity user, UUID albumId, UUID photoId);

    List<AlbumEntity> findByUserAndPhotos_Id(UserEntity user, UUID photoId);

    List<AlbumEntity> findAllByUser(UserEntity user);

    Optional<AlbumEntity> findByIdAndUser(UUID id, UserEntity user);
}