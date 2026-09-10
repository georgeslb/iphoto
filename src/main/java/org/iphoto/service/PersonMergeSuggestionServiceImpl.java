package org.iphoto.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iphoto.entities.PersonEmbeddingBucketEntity;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PersonMergeSuggestionEntity;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.repository.PersonMergeSuggestionRepository;
import org.iphoto.util.EmbeddingCodec;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class PersonMergeSuggestionServiceImpl implements PersonMergeSuggestionService {

    // Deliberately much stricter than FaceRecognitionServiceImpl.THRESHOLD (0.60 - used to attach
    // ONE face to an existing person): this compares two whole persons' aggregate identities
    // against each other to surface a suggestion for a HUMAN to confirm, not to auto-merge. A
    // lower bar here would nag the Library page banner with too many false positives. Needs
    // empirical tuning against real accounts once this ships - start conservative, same as every
    // other similarity threshold in this codebase.
    private static final double SUGGESTION_THRESHOLD = 0.75;

    private final PersonMergeSuggestionRepository personMergeSuggestionRepository;

    @Override
    @Transactional
    public void refreshSuggestions(SpaceEntity space, List<PersonEntity> persons,
                                    Map<UUID, List<PersonEmbeddingBucketEntity>> bucketsByPerson) {
        // One overall (all-years) embedding per person, computed once rather than per pair below -
        // unlike FaceRecognitionServiceImpl's per-face matching (which only ever needs a person's
        // NEAREST-year buckets to compare against one face's specific year), comparing two whole
        // identities against each other has no "target year" to weigh toward, so every bucket the
        // person has contributes.
        Map<UUID, float[]> overallByPerson = new HashMap<>();
        for (PersonEntity person : persons) {
            overallEmbedding(bucketsByPerson.getOrDefault(person.getId(), List.of()))
                    .ifPresent(vector -> overallByPerson.put(person.getId(), vector));
        }

        for (int i = 0; i < persons.size(); i++) {
            PersonEntity personI = persons.get(i);
            float[] vectorI = overallByPerson.get(personI.getId());
            if (vectorI == null) {
                continue; // no faces/buckets yet - nothing to compare
            }
            for (int j = i + 1; j < persons.size(); j++) {
                PersonEntity personJ = persons.get(j);
                float[] vectorJ = overallByPerson.get(personJ.getId());
                if (vectorJ == null || vectorJ.length != vectorI.length) {
                    // Missing buckets, or a dimension mismatch (most likely a different
                    // embedding-model generation coexisting from before a model swap - see
                    // FaceRecognitionServiceImpl.scoreAgainstPerson's identical defensive skip).
                    continue;
                }
                double similarity = cosineSimilarity(vectorI, vectorJ);
                try {
                    upsertOrDiscard(space.getId(), personI, personJ, similarity);
                } catch (Exception e) {
                    log.error("could not refresh merge suggestion between persons {} and {}",
                            personI.getId(), personJ.getId(), e);
                }
            }
        }
    }

    // Canonicalizes the pair (smaller id always personA) so a pair is only ever stored/looked up
    // one way regardless of which order the caller happened to compare them in.
    private void upsertOrDiscard(UUID spaceId, PersonEntity a, PersonEntity b, double similarity) {
        PersonEntity lower = a.getId().compareTo(b.getId()) <= 0 ? a : b;
        PersonEntity higher = lower == a ? b : a;
        Optional<PersonMergeSuggestionEntity> existing =
                personMergeSuggestionRepository.findByPersonAIdAndPersonBId(lower.getId(), higher.getId());

        if (similarity < SUGGESTION_THRESHOLD) {
            // No longer similar enough (e.g. a face was unlinked/moved since the last run) - drop
            // a previously-suggested pair rather than leave a stale suggestion sitting around.
            existing.ifPresent(personMergeSuggestionRepository::delete);
            return;
        }

        PersonMergeSuggestionEntity suggestion = existing.orElseGet(() -> PersonMergeSuggestionEntity.builder()
                .personA(lower)
                .personB(higher)
                .build());
        suggestion.setSpaceId(spaceId);
        suggestion.setSimilarity(similarity);
        personMergeSuggestionRepository.save(suggestion);
    }

    @Override
    public Page<PersonMergeSuggestionEntity> getSuggestionsForSpace(UUID spaceId, Pageable pageable) {
        return personMergeSuggestionRepository.findBySpaceIdOrderBySimilarityDesc(spaceId, pageable);
    }

    @Override
    public long countSuggestionsForSpace(UUID spaceId) {
        return personMergeSuggestionRepository.countBySpaceId(spaceId);
    }

    @Override
    @Transactional
    public void discardSuggestionsInvolving(UUID personId) {
        personMergeSuggestionRepository.deleteByPersonAIdOrPersonBId(personId, personId);
    }

    // Weighted average across EVERY bucket a person has (not just the nearest-year ones
    // scoreAgainstPerson uses) - comparing two whole identities has no single "target year" to
    // weigh toward, so every calendar year they appear in should contribute.
    private static Optional<float[]> overallEmbedding(List<PersonEmbeddingBucketEntity> buckets) {
        float[] running = null;
        int totalCount = 0;
        for (PersonEmbeddingBucketEntity bucket : buckets) {
            float[] vector = EmbeddingCodec.toFloats(bucket.getEmbedding());
            if (vector.length == 0) {
                continue;
            }
            if (running == null) {
                running = vector.clone();
                totalCount = bucket.getFaceCount();
                continue;
            }
            if (vector.length != running.length) {
                continue; // defensive - see the identical skip in refreshSuggestions above
            }
            int newTotal = totalCount + bucket.getFaceCount();
            for (int k = 0; k < running.length; k++) {
                running[k] = (running[k] * totalCount + vector[k] * bucket.getFaceCount()) / newTotal;
            }
            totalCount = newTotal;
        }
        return Optional.ofNullable(running);
    }

    private static double cosineSimilarity(float[] vectorA, float[] vectorB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += (double) vectorA[i] * vectorA[i];
            normB += (double) vectorB[i] * vectorB[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
