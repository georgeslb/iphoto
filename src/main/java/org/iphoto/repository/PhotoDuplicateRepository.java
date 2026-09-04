package org.iphoto.repository;

import org.iphoto.configuration.MainSpaceLimited;
import org.iphoto.entities.PhotoDuplicateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository

public interface PhotoDuplicateRepository extends JpaRepository<PhotoDuplicateEntity, UUID> {

    @MainSpaceLimited
    List<PhotoDuplicateEntity> findByPhotoIdIn(List<UUID> ids);

    @MainSpaceLimited
    void deleteByPhotoIdIn(List<UUID> uuids);
}