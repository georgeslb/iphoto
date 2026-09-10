package org.iphoto.service;

import org.iphoto.entities.PersonEmbeddingBucketEntity;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PersonMergeSuggestionEntity;
import org.iphoto.entities.SpaceEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// "These two people might be the same person" - an extra step run alongside face recognition
// (FaceRecognitionServiceImpl.groupFacesForUser), separate from and much stricter than the
// per-face match threshold used to attach one face to an existing person. Never merges anything
// itself - only ever surfaces a suggestion for a human to review via the existing, explicit
// PersonRecognitionService.mergePersons flow (Library page banner -> popup -> merge/next/close).
public interface PersonMergeSuggestionService {

    // Compares every distinct pair among `persons` (all belonging to `space`) against each other
    // using their overall (all-years) embedding, and upserts a suggestion row for any pair similar
    // enough to plausibly be the same real person - or removes a stale one that no longer clears
    // the bar (e.g. after a face was unlinked/moved elsewhere). Reuses the exact `persons`/
    // `bucketsByPerson` snapshot groupFacesForUser already loaded for this run (mutated as
    // brand-new people are promoted mid-run) rather than issuing its own queries.
    void refreshSuggestions(SpaceEntity space, List<PersonEntity> persons, Map<UUID, List<PersonEmbeddingBucketEntity>> bucketsByPerson);

    // One page of live suggestions for one space, most-similar first - backs the Library page
    // banner/popup (FaceService.getMergeSuggestions maps these to PersonMergeSuggestionDTOs). The
    // popup only ever shows one pair at a time, so the caller always asks for size=1 - see
    // FaceController#getPersonMergeSuggestions for why this is paginated at all.
    Page<PersonMergeSuggestionEntity> getSuggestionsForSpace(UUID spaceId, Pageable pageable);

    // Cheap count-only read for the banner text ("N people might be duplicates") - doesn't pay for
    // even a single suggestion's face thumbnails just to render a number.
    long countSuggestionsForSpace(UUID spaceId);

    // Called after a person is merged away (PersonRecognitionService#mergeFacesIntoTarget) - the
    // target's own aggregate embedding just changed, so any suggestion mentioning either side of
    // the merge is stale. The FK's own "on delete cascade" already removes rows mentioning the
    // now-deleted source; this additionally clears rows mentioning the surviving target so the
    // next scheduled run recomputes it fresh instead of leaving a stale row in place.
    void discardSuggestionsInvolving(UUID personId);
}
