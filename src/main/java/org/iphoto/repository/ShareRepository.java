package org.iphoto.repository;

import org.iphoto.entities.ShareEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShareRepository extends JpaRepository<ShareEntity, UUID> {
    Optional<ShareEntity> findByName(String name);

}
