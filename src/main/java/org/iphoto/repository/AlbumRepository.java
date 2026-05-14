package org.iphoto.repository;

import org.iphoto.entities.AlbumEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AlbumRepository extends JpaRepository<AlbumEntity, UUID> {
    Optional<AlbumEntity> findByName(String name);


    boolean existsByIdAndPhotos_Id(UUID albumId, UUID photoId);
}
