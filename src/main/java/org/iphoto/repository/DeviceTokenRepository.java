package org.iphoto.repository;

import org.iphoto.entities.DeviceTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceTokenRepository extends JpaRepository<DeviceTokenEntity, UUID> {

    // No @MainSpaceLimited here (unlike PhotoRepository/AlbumRepository etc.) - that aspect scopes
    // photo-domain queries to a Space via SecurityContextHolder; a token lookup happens BEFORE
    // authentication is established (this is what establishes it) and is scoped by user_id
    // directly instead.
    @Query("SELECT dt FROM DeviceTokenEntity dt JOIN FETCH dt.user WHERE dt.tokenHash = :tokenHash AND dt.revokedAt IS NULL")
    Optional<DeviceTokenEntity> findByTokenHashAndRevokedAtIsNull(String tokenHash);


    List<DeviceTokenEntity> findByUser_IdOrderByCreatedAtDesc(UUID userId);
}
