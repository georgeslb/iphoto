package org.iphoto.repository;

import org.iphoto.configuration.MainSpaceLimited;
import org.iphoto.entities.ShareEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    // Public/guest-facing lookups below must NOT be @MainSpaceLimited: a guest share session
    // has no matching UserEntity, so the space-filter aspect would throw trying to resolve one.

    Optional<ShareEntity> findByUserMd5AndName(String userMd5, String name);

    @Query("select s from ShareEntity s where s.id = :id")
    Optional<ShareEntity> findByIdIgnoringSpace(@Param("id") UUID id);
}
