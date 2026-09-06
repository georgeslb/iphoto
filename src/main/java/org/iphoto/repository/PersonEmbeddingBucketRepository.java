package org.iphoto.repository;

import org.iphoto.entities.PersonEmbeddingBucketEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PersonEmbeddingBucketRepository extends JpaRepository<PersonEmbeddingBucketEntity, UUID> {

    Optional<PersonEmbeddingBucketEntity> findByPersonIdAndBucketYear(UUID personId, int bucketYear);

    List<PersonEmbeddingBucketEntity> findByPersonId(UUID personId);

    // Batch-loads every candidate person's buckets in one query per FaceRecognitionService run
    // (see groupFacesForUser) instead of one query per (face, person) pair - the whole point of
    // precomputing/persisting these in the first place.
    List<PersonEmbeddingBucketEntity> findByPersonIdIn(Collection<UUID> personIds);
}
