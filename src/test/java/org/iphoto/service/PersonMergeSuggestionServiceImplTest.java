package org.iphoto.service;

import org.iphoto.AbstractIntegrationTest;
import org.iphoto.entities.PersonEmbeddingBucketEntity;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PersonMergeSuggestionEntity;
import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.UserEntity;
import org.iphoto.repository.PersonEmbeddingBucketRepository;
import org.iphoto.repository.PersonMergeSuggestionRepository;
import org.iphoto.repository.PersonRepository;
import org.iphoto.util.EmbeddingCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Regression coverage for a real production incident: refreshSuggestions used to run one
// findByPersonAIdAndPersonBId query PER comparison pair, inside a single long @Transactional
// method - fine for a handful of people, but a space with a few hundred people means tens of
// thousands of pairs, and every one of those queries triggered a Hibernate auto-flush over an
// ever-growing persistence context (every suggestion saved earlier in the same run stayed
// managed) - see PersonMergeSuggestionRepository.findBySpaceId's own comment. That turned what
// should be a sub-second pass into a multi-hour, CPU-pegged hang that starved the whole JVM (the
// single dedicated face-recognition thread never returned, and Tomcat/GC were starved alongside
// it). The fix batches the existing-suggestions lookup into one query up front - these tests cover
// that the create/update/delete behavior is still correct after that change, not just that it's
// fast (this suite has no practical way to assert query counts against the real bug's own scale).
class PersonMergeSuggestionServiceImplTest extends AbstractIntegrationTest {

    private static final float[] DIRECTION_A = {1f, 0f, 0f};
    private static final float[] DIRECTION_B = {0f, 1f, 0f}; // cos(A, B) = 0, well below threshold
    private static final float[] DIRECTION_C = {0f, 0f, 1f}; // cos(A, C) = cos(B, C) = 0 too

    @Autowired
    private PersonMergeSuggestionService personMergeSuggestionService;
    @Autowired
    private PersonMergeSuggestionRepository personMergeSuggestionRepository;
    @Autowired
    private PersonRepository personRepository;
    @Autowired
    private PersonEmbeddingBucketRepository personEmbeddingBucketRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private SpaceService spaceService;

    private SpaceEntity createTestSpace() {
        UserEntity user = userService.createUser("merge-suggestion-" + UUID.randomUUID(), "password", "USER");
        return spaceService.getDefaultSpaceForUser(user);
    }

    private PersonEntity createPerson(SpaceEntity space, float[] embedding) {
        PersonEntity person = personRepository.save(PersonEntity.builder().space(space).build());
        personEmbeddingBucketRepository.save(PersonEmbeddingBucketEntity.builder()
                .person(person)
                .bucketYear(2024)
                .faceCount(1)
                .embedding(EmbeddingCodec.toBytes(embedding))
                .build());
        return person;
    }

    private void setBucketEmbedding(PersonEntity person, float[] embedding) {
        PersonEmbeddingBucketEntity bucket = personEmbeddingBucketRepository.findByPersonId(person.getId()).get(0);
        bucket.setEmbedding(EmbeddingCodec.toBytes(embedding));
        personEmbeddingBucketRepository.save(bucket);
    }

    private void refresh(SpaceEntity space, List<PersonEntity> persons) {
        Map<UUID, List<PersonEmbeddingBucketEntity>> bucketsByPerson = new HashMap<>();
        for (PersonEntity person : persons) {
            bucketsByPerson.put(person.getId(), personEmbeddingBucketRepository.findByPersonId(person.getId()));
        }
        personMergeSuggestionService.refreshSuggestions(space, persons, bucketsByPerson);
    }

    private Optional<PersonMergeSuggestionEntity> suggestionBetween(SpaceEntity space, UUID personAId, UUID personBId) {
        return personMergeSuggestionRepository.findBySpaceId(space.getId()).stream()
                .filter(s -> (s.getPersonA().getId().equals(personAId) && s.getPersonB().getId().equals(personBId))
                        || (s.getPersonA().getId().equals(personBId) && s.getPersonB().getId().equals(personAId)))
                .findFirst();
    }

    @Test
    void createsUpdatesAndDeletesSuggestionsAsSimilarityChangesAcrossRuns() {
        SpaceEntity space = createTestSpace();
        PersonEntity alice = createPerson(space, DIRECTION_A);
        PersonEntity aliceAgain = createPerson(space, DIRECTION_A); // cos(alice, aliceAgain) = 1.0
        PersonEntity bob = createPerson(space, DIRECTION_B);
        List<PersonEntity> persons = List.of(alice, aliceAgain, bob);

        refresh(space, persons);

        Optional<PersonMergeSuggestionEntity> aliceSuggestion = suggestionBetween(space, alice.getId(), aliceAgain.getId());
        assertThat(aliceSuggestion).isPresent();
        assertThat(aliceSuggestion.get().getSimilarity()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(suggestionBetween(space, alice.getId(), bob.getId())).isEmpty();
        assertThat(suggestionBetween(space, aliceAgain.getId(), bob.getId())).isEmpty();
        assertThat(personMergeSuggestionRepository.countBySpaceId(space.getId())).isEqualTo(1);

        // Re-running with nothing changed must update the SAME row in place (the whole point of
        // existingByPair's prefetch), never create a duplicate - the DB's own unique constraint on
        // (person_a_id, person_b_id) would otherwise fail this outright.
        UUID suggestionId = aliceSuggestion.get().getId();
        refresh(space, persons);
        assertThat(personMergeSuggestionRepository.countBySpaceId(space.getId())).isEqualTo(1);
        assertThat(suggestionBetween(space, alice.getId(), aliceAgain.getId()).get().getId()).isEqualTo(suggestionId);

        // Similarity drops back below threshold (e.g. a face was unlinked/moved) - the existing
        // suggestion must be found via the prefetched map and deleted, not left stale. DIRECTION_C
        // (not DIRECTION_B) so aliceAgain doesn't incidentally start matching bob instead.
        setBucketEmbedding(aliceAgain, DIRECTION_C);
        refresh(space, persons);
        assertThat(suggestionBetween(space, alice.getId(), aliceAgain.getId())).isEmpty();
        assertThat(personMergeSuggestionRepository.countBySpaceId(space.getId())).isEqualTo(0);
    }
}
