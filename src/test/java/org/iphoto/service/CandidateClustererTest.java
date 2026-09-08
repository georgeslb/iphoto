package org.iphoto.service;

import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.util.EmbeddingCodec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Plain JUnit unit test - no Spring context, no database. CandidateClusterer is deliberately kept
// standalone/dependency-free specifically so this is possible (see its own class comment) - unlike
// the rest of this project's test suite (which extends AbstractIntegrationTest even for
// logic-only classes), FaceRecognitionService is one of the interfaces explicitly excluded from the
// per-interface integration-test suite (see TODO.md's "Per-interface test suite added" entry), so
// this is the only realistic way to get fast, direct coverage of this specific matching/promotion
// logic. See TODO.md's "qualif-server item 5" entry (test plan) for the full rationale behind each
// test below.
//
// Uses distinct-direction vectors per synthetic "person"/cluster (axis-aligned unit vectors, cosine
// similarity 0 between any two distinct directions) rather than this suite's more common
// {v,v,v}-shaped test embeddings - a shared {v,v,v} shape is cosine-parallel to every other {v,v,v}
// vector regardless of value (cosine similarity is scale-invariant), which would make every
// synthetic face in a test "match" every other one and defeat the point of these tests. See
// TODO.md's "Not done, considered and rejected for now" note (the entry above item 5) for where
// this exact collision was first hit and fixed.
class CandidateClustererTest {

    private static final double THRESHOLD = 0.60;
    private static final Duration NINETY_DAYS = Duration.ofDays(90);
    private static final int PROMOTE_AT = 3;

    private static final float[] DIRECTION_A = {1f, 0f, 0f};
    private static final float[] DIRECTION_B = {0f, 1f, 0f};
    private static final float[] DIRECTION_C = {0f, 0f, 1f};
    private static final float[] DIRECTION_D = {-1f, 0f, 0f}; // cos(A, D) = -1, well below THRESHOLD

    private static FaceEntity face(float[] direction, LocalDateTime dateTaken) {
        return FaceEntity.builder()
                .id(UUID.randomUUID())
                .photo(PhotoEntity.builder().dateTaken(dateTaken).build())
                .x(0).y(0).width(1).height(1)
                .embedding(EmbeddingCodec.toBytes(direction))
                .build();
    }

    @Test
    void promotesTwoDistinctPeopleEachAtExactlyThreeMembersAndNeverPromotesSingletonsOrDuos() {
        CandidateClusterer clusterer = new CandidateClusterer(THRESHOLD, NINETY_DAYS, PROMOTE_AT);
        LocalDateTime t = LocalDateTime.of(2024, 1, 1, 0, 0);

        // "Alice" - 3 mutually-similar faces.
        FaceEntity alice1 = face(DIRECTION_A, t);
        FaceEntity alice2 = face(DIRECTION_A, t.plusDays(1));
        FaceEntity alice3 = face(DIRECTION_A, t.plusDays(2));
        assertThat(clusterer.add(alice1)).isEmpty();
        assertThat(clusterer.add(alice2)).isEmpty();
        Optional<List<FaceEntity>> alicePromotion = clusterer.add(alice3);

        // "Bob" - 3 mutually-similar faces, a different direction from Alice's.
        FaceEntity bob1 = face(DIRECTION_B, t.plusDays(3));
        FaceEntity bob2 = face(DIRECTION_B, t.plusDays(4));
        FaceEntity bob3 = face(DIRECTION_B, t.plusDays(5));
        assertThat(clusterer.add(bob1)).isEmpty();
        assertThat(clusterer.add(bob2)).isEmpty();
        Optional<List<FaceEntity>> bobPromotion = clusterer.add(bob3);

        // Noise - isolated faces matching nothing else, never promoted.
        assertThat(clusterer.add(face(DIRECTION_C, t.plusDays(6)))).isEmpty();
        assertThat(clusterer.add(face(DIRECTION_D, t.plusDays(7)))).isEmpty();

        // FaceEntity has no field-based equals (see BaseEntity/Lombok config) - compare by id
        // rather than object equality/containsExactly.
        assertThat(alicePromotion).isPresent();
        assertThat(alicePromotion.get()).extracting(FaceEntity::getId)
                .containsExactly(alice1.getId(), alice2.getId(), alice3.getId());
        assertThat(bobPromotion).isPresent();
        assertThat(bobPromotion.get()).extracting(FaceEntity::getId)
                .containsExactly(bob1.getId(), bob2.getId(), bob3.getId());
    }

    @Test
    void promotionPayloadIsExactlyTheThreeFacesThatJoinedNotEveryFaceThatEverMatchedTheCluster() {
        CandidateClusterer clusterer = new CandidateClusterer(THRESHOLD, NINETY_DAYS, PROMOTE_AT);
        LocalDateTime t = LocalDateTime.of(2024, 3, 1, 0, 0);

        FaceEntity f1 = face(DIRECTION_A, t);
        FaceEntity f2 = face(DIRECTION_A, t.plusDays(1));
        FaceEntity f3 = face(DIRECTION_A, t.plusDays(2));
        FaceEntity f4 = face(DIRECTION_A, t.plusDays(3)); // arrives AFTER promotion

        assertThat(clusterer.add(f1)).isEmpty();
        assertThat(clusterer.add(f2)).isEmpty();
        Optional<List<FaceEntity>> promotion = clusterer.add(f3);
        assertThat(promotion).isPresent();
        assertThat(promotion.get()).extracting(FaceEntity::getId)
                .containsExactly(f1.getId(), f2.getId(), f3.getId());

        // The promoted cluster is discarded - a 4th still-matching face must NOT silently join it
        // (guards against a growing list here, and against ever promoting the same cluster twice).
        // The caller (FaceRecognitionServiceImpl) is responsible for routing faces like this one to
        // the newly-created person via the normal existing-person path instead.
        assertThat(clusterer.add(f4)).isEmpty();
    }

    @Test
    void ninetyDayGateLetsFacesFortyEightHoursApartClusterTogetherWhereTheOldTwentyFourHourGateWouldNotHave() {
        LocalDateTime t = LocalDateTime.of(2024, 5, 1, 0, 0);
        FaceEntity f1 = face(DIRECTION_A, t);
        FaceEntity f2 = face(DIRECTION_A, t.plusHours(48));
        FaceEntity f3 = face(DIRECTION_A, t.plusHours(96));

        CandidateClusterer widenedGate = new CandidateClusterer(THRESHOLD, Duration.ofDays(90), PROMOTE_AT);
        assertThat(widenedGate.add(f1)).isEmpty();
        assertThat(widenedGate.add(f2)).isEmpty();
        Optional<List<FaceEntity>> promotion = widenedGate.add(f3);
        assertThat(promotion).isPresent();
        assertThat(promotion.get()).extracting(FaceEntity::getId)
                .containsExactly(f1.getId(), f2.getId(), f3.getId());

        // Same three faces, same gaps (each pair 48h apart, well beyond the old 24h gate) - under
        // the ORIGINAL tight gate this file's item 5 replaced, none of them would ever have been
        // considered close enough to compare, so none should ever promote.
        CandidateClusterer oldGate = new CandidateClusterer(THRESHOLD, Duration.ofHours(24), PROMOTE_AT);
        assertThat(oldGate.add(f1)).isEmpty();
        assertThat(oldGate.add(f2)).isEmpty();
        assertThat(oldGate.add(f3)).isEmpty();
    }

    @Test
    void aPairMoreThanNinetyDaysApartStillDoesNotCluster() {
        CandidateClusterer clusterer = new CandidateClusterer(THRESHOLD, Duration.ofDays(90), PROMOTE_AT);
        LocalDateTime t = LocalDateTime.of(2024, 1, 1, 0, 0);

        FaceEntity f1 = face(DIRECTION_A, t);
        FaceEntity f2 = face(DIRECTION_A, t.plusDays(91));
        FaceEntity f3 = face(DIRECTION_A, t.plusDays(182));

        // Each pair is >90 days apart, so despite matching direction, none should ever join the
        // same cluster - three separate one-member clusters, none promoted.
        assertThat(clusterer.add(f1)).isEmpty();
        assertThat(clusterer.add(f2)).isEmpty();
        assertThat(clusterer.add(f3)).isEmpty();
    }
}
