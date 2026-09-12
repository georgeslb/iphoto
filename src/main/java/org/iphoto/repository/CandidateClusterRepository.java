package org.iphoto.repository;

import org.iphoto.entities.CandidateClusterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CandidateClusterRepository extends JpaRepository<CandidateClusterEntity, UUID> {

    // FaceRecognitionServiceImpl.groupFacesForUser's one seed-load per run - explicit space_id
    // filter, NOT @MainSpaceLimited, same reasoning as every other repository this heavy-task
    // thread reads (see FaceRepository.findNewBySpaceIdFetchPhoto's own comment).
    // Partitioned in memory (not a second query) into still-plausible vs. expired (see
    // CANDIDATE_CLUSTER_MAX_AGE) - this list is normally tiny (open clusters are the exception, not
    // the rule; most either promote or never get a 2nd member at all), so one query is enough.
    List<CandidateClusterEntity> findBySpaceId(UUID spaceId);
}
