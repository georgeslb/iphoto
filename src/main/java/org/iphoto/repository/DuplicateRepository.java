package org.iphoto.repository;

import org.iphoto.configuration.MainSpaceLimited;
import org.iphoto.entities.DuplicateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository

public interface DuplicateRepository extends JpaRepository<DuplicateEntity, UUID> {

    @MainSpaceLimited
    List<DuplicateEntity> findByPhotoDuplicatesPhotoIdIn(List<UUID> ids);
    @MainSpaceLimited
    Optional<DuplicateEntity> findByHash(String hash);
}