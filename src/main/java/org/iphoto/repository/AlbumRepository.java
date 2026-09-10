package org.iphoto.repository;

import org.iphoto.configuration.MainSpaceLimited;
import org.iphoto.entities.AlbumEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;


import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AlbumRepository extends JpaRepository<AlbumEntity, UUID> {

    @MainSpaceLimited
    Optional<AlbumEntity> findByName(String name);

    @MainSpaceLimited
    boolean existsByIdAndPhotos_Id(UUID albumId, UUID photoId);

    @MainSpaceLimited
    List<AlbumEntity> findByPhotos_Id(UUID photoId);

    @MainSpaceLimited
    List<AlbumEntity> findAll();

    @MainSpaceLimited
    @Query("SELECT p FROM AlbumEntity p WHERE p.id = :id")
    Optional<AlbumEntity> findByIdInMainSpace(UUID id);

    // Idempotency lookup for the Immich album import - lets a re-run find the album it already
    // created instead of hitting findByName's collision throw or creating a duplicate.
    @MainSpaceLimited
    Optional<AlbumEntity> findByExternalSourceAndExternalId(String externalSource, String externalId);

    // Public/guest-facing lookup for the share flow — must NOT be @MainSpaceLimited: a guest
    // share session has no matching UserEntity, so the space-filter aspect would throw trying
    // to resolve one. Album-level authorization for guests is enforced separately (share ->
    // album ownership check in ShareAccessFilter/AlbumService), not by the space filter.
    @Query("select a from AlbumEntity a where a.id = :id")
    Optional<AlbumEntity> findByIdIgnoringSpace(@Param("id") UUID id);
}