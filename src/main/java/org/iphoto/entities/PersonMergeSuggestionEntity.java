package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.UUID;

// "These two people might be the same person" - see PersonMergeSuggestionService. Written by an
// extra step alongside FaceRecognitionServiceImpl.groupFacesForUser (comparing whole persons'
// aggregate embeddings against each other, not one face against a person), surfaced on the
// Library page for a human to review; merging is still the existing, explicit
// PersonRecognitionService.mergePersons flow - this table only ever suggests, never merges
// automatically.
//
// Pure derived/cache data, same as PersonEmbeddingBucketEntity - cascades away entirely when
// either person it names is deleted (@OnDelete below + the FK's own "on delete cascade" in
// V2__add_person_merge_suggestions.sql), so a merged-away or deleted person can never leave a
// dangling suggestion behind.
@Entity
@Table(name = "person_merge_suggestions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_person_merge_suggestions_person_a_id_person_b_id", columnNames = {"person_a_id", "person_b_id"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonMergeSuggestionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_a_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private PersonEntity personA;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_b_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private PersonEntity personB;

    // Stored directly rather than derived from personA/personB's own space_id at query time -
    // PersonMergeSuggestionServiceImpl#refreshSuggestions runs on TaskWorkerManager's dedicated
    // heavy-task thread pool, where MainSpaceAspectConfiguration deliberately treats
    // @MainSpaceLimited as a no-op (same reasoning as PersonRepository.findAllBySpaceId). Reading
    // by this column directly is correct regardless of which thread a caller runs on.
    @Column(name = "space_id", nullable = false)
    private UUID spaceId;

    @Column(nullable = false)
    private double similarity;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
