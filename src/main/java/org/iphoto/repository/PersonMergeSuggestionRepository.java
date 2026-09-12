package org.iphoto.repository;

import org.iphoto.entities.PersonMergeSuggestionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    // PersonMergeSuggestionServiceImpl#refreshSuggestions' one upfront batch load of this space's
    // existing suggestions - replaces what used to be one findByPersonAIdAndPersonBId query PER
    // comparison pair (a space with even a few hundred people means tens of thousands of pairs).
    // Every one of those ran inside the same long @Transactional method, so Hibernate's auto-flush
    // fired before each one, re-checking an ever-growing persistence context (every suggestion
    // saved earlier in the run stayed managed) - effectively quadratic on top of the already
    // quadratic pair count. That turned a run that should take seconds into a multi-hour,
    // CPU-pegged hang that starved the whole JVM (production incident, see TODO.md). One query
    // here plus an in-memory Map lookup per pair fixes both the query count and the flush growth.
    List<PersonMergeSuggestionEntity> findBySpaceId(UUID spaceId);

    // Called when a person is merged away or its similarity to another person drops back below
    // the suggestion threshold - passing the same id for both params matches any row mentioning
    // that person on either side. Plain derived delete (no @Query), same convention as
    // FaceRepository#deleteByPhotoId/PhotoDuplicateRepository#deleteByPhotoIdIn - no @Modifying
    // needed for this style.
    void deleteByPersonAIdOrPersonBId(UUID personAId, UUID personBId);
}
