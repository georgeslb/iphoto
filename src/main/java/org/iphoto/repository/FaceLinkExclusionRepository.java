package org.iphoto.repository;

import org.iphoto.entities.FaceLinkExclusionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface FaceLinkExclusionRepository extends JpaRepository<FaceLinkExclusionEntity, UUID> {

    boolean existsByFaceIdAndExcludedPersonId(UUID faceId, UUID excludedPersonId);

    // Bulk-loaded once per FaceRecognitionService run (keyed by face id) instead of querying per
    // face/person pair — avoids an exists() query per (face, candidate person) combination.
    List<FaceLinkExclusionEntity> findByFaceIdIn(Collection<UUID> faceIds);

    @Modifying
    @Query("delete from FaceLinkExclusionEntity e where e.face.id = :faceId and e.excludedPerson.id = :personId")
    void deleteByFaceIdAndExcludedPersonId(@Param("faceId") UUID faceId, @Param("personId") UUID personId);
}
