package org.iphoto.repository;

import org.iphoto.entities.PhotoMetadataEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
public interface PhotoMetadataRepository extends JpaRepository<PhotoMetadataEntity, UUID> {
}
