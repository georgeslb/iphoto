package org.iphoto.repository;

import org.iphoto.entities.DuplicateEntity;
import org.iphoto.entities.UserEntity; // Import UserEntity
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
    List<DuplicateEntity> findByPhotosIdIn(List<UUID> ids);

    Slice<DuplicateEntity> findByUserAndStatus(UserEntity user, DuplicateStatus duplicateStatus, Pageable pageable);

    Optional<DuplicateEntity> findByUserAndHash(UserEntity user, String hash);

    void deleteByIdAndPhotosId(UUID duplicateId, UUID photoId);
}