package org.iphoto.repository;

import org.iphoto.entities.ShareEntity;
import org.iphoto.entities.UserEntity; // Import UserEntity
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShareRepository extends JpaRepository<ShareEntity, UUID> {
    Optional<ShareEntity> findByUserAndName(UserEntity user, String name);

    List<ShareEntity> findAllByUser(UserEntity user);

    Optional<ShareEntity> findByIdAndUser(UUID id, UserEntity user);
}