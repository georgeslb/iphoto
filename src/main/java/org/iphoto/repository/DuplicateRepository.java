package org.iphoto.repository;

import org.iphoto.configuration.MainSpaceLimited;
import org.iphoto.entities.DuplicateEntity;
import org.iphoto.model.DuplicateStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository

public interface DuplicateRepository extends JpaRepository<DuplicateEntity, UUID> {

    @MainSpaceLimited
    List<DuplicateEntity> findByPhotosIdIn(List<UUID> ids);
    @MainSpaceLimited
    Slice<DuplicateEntity> findByStatus(DuplicateStatus duplicateStatus, Pageable pageable);
    @MainSpaceLimited
    Optional<DuplicateEntity> findByHash(String hash);
    @MainSpaceLimited
    void deleteByIdAndPhotosId(UUID duplicateId, UUID photoId);
}