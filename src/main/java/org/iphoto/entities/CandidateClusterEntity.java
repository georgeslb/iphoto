package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.UUID;

// A still-open (1-2 member, not yet promoted to a real PersonEntity) candidate person cluster -
// see FaceRecognitionServiceImpl.groupFacesForUser and CandidateClusterer's own class comment for
// the full incremental-clustering design this backs. Member faces are found via
// FaceEntity.candidateCluster (a plain FK on faces, not a join table here) - this entity itself
// carries nothing but identity/age, since a match check only ever needs each member face's own
// embedding/photo.dateTaken, already on FaceEntity.
//
// space_id stored directly (not derived via a member face's own photo.space_id at query time) -
// same reasoning as PersonMergeSuggestionEntity's own space_id: the writer runs on
// TaskWorkerManager's heavy-task thread pool, where @MainSpaceLimited would silently be a no-op.
//
// Persistable<UUID>, exactly like PhotoEntity/BaseEntity's own isNew mechanism (see PersonEntity's
// own comment for the full "why" this pattern exists): the caller (FaceRecognitionServiceImpl)
// always pre-assigns this entity's id BEFORE the first save() - CandidateClusterer.add() hands back
// a real, usable id for a brand-new cluster without touching a database itself (see its own class
// comment), and the SAME id is used for the id-bearing FaceEntity.candidateCluster FK it sets in
// the same run. id is deliberately NOT @GeneratedValue - same reasoning as PhotoEntity's own plain
// client-assigned @Id: a generator strategy combined with an already-populated id before persist()
// is exactly what makes Hibernate suspect a DETACHED instance rather than a genuinely new one
// (hit this directly: persist() failed with "detached entity passed to persist" the moment an id
// was pre-set on a @GeneratedValue(UUID) field). Persistable.isNew() is still needed on top of
// dropping @GeneratedValue - without it, Spring Data's default isNew() check (id == null) would see
// the pre-assigned, non-null id and call merge() instead of persist(), which doesn't reliably
// insert the row before a same-transaction statement that references it by FK gets flushed (the
// "referential integrity constraint violation" this whole mechanism fixes - caught the hard way:
// the FK check failed at commit, not at save() itself, so it silently tripped this method's own
// per-face try/catch instead of failing loudly).
@Entity
@Table(name = "candidate_clusters")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateClusterEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "space_id", nullable = false)
    private UUID spaceId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Transient
    @Builder.Default
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        isNew = false;
    }

    @PostLoad
    private void markNotNew() {
        isNew = false;
    }
}
