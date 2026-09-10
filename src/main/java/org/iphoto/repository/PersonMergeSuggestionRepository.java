package org.iphoto.repository;

import org.iphoto.entities.PersonMergeSuggestionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PersonMergeSuggestionRepository extends JpaRepository<PersonMergeSuggestionEntity, UUID> {

    // Library page banner/popup - most-confident suggestion first, one page at a time (the popup
    // only ever shows one pair - see FaceController#getPersonMergeSuggestions for why this is
    // paginated at all despite that). Filtered explicitly by space_id (not @MainSpaceLimited) so
    // this reads correctly regardless of which thread wrote the rows - see
    // PersonMergeSuggestionEntity's own comment.
    Page<PersonMergeSuggestionEntity> findBySpaceIdOrderBySimilarityDesc(UUID spaceId, Pageable pageable);

    // Cheap count-only query backing the banner text - no join to either PersonEntity/FaceEntity,
    // so it never pays for the face thumbnails a real page fetch above would.
    long countBySpaceId(UUID spaceId);

    // Upsert lookup - PersonMergeSuggestionServiceImpl always canonicalizes a pair so the smaller
    // id is personA/the larger is personB before calling this, so a pair is only ever looked up
    // one way regardless of comparison order.
    Optional<PersonMergeSuggestionEntity> findByPersonAIdAndPersonBId(UUID personAId, UUID personBId);

    // Called when a person is merged away or its similarity to another person drops back below
    // the suggestion threshold - passing the same id for both params matches any row mentioning
    // that person on either side. Plain derived delete (no @Query), same convention as
    // FaceRepository#deleteByPhotoId/PhotoDuplicateRepository#deleteByPhotoIdIn - no @Modifying
    // needed for this style.
    void deleteByPersonAIdOrPersonBId(UUID personAId, UUID personBId);
}
