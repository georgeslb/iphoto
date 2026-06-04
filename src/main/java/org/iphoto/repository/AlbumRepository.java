package org.iphoto.repository;

import org.iphoto.configuration.MainSpaceLimited;
import org.iphoto.entities.AlbumEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;


import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AlbumRepository extends JpaRepository<AlbumEntity, UUID> {

    @MainSpaceLimited
    Optional<AlbumEntity> findByName(String name);

    @MainSpaceLimited
    boolean existsByIdAndPhotos_Id(UUID albumId, UUID photoId);

    @MainSpaceLimited
    List<AlbumEntity> findByPhotos_Id(UUID photoId);

    @MainSpaceLimited
    List<AlbumEntity> findAll();

    @MainSpaceLimited
    Optional<AlbumEntity> findById(UUID id);
}