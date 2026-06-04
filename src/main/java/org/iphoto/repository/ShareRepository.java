package org.iphoto.repository;

import org.iphoto.configuration.MainSpaceLimited;
import org.iphoto.entities.ShareEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShareRepository extends JpaRepository<ShareEntity, UUID> {

    @MainSpaceLimited
    Optional<ShareEntity> findByName(String name);

    @MainSpaceLimited
    List<ShareEntity> findAll();

    @MainSpaceLimited
    Optional<ShareEntity> findById(UUID id);

    Optional<ShareEntity>  findByAlbumIdAndName(UUID albumId, String name);
}