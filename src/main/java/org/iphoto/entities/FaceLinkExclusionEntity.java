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

// Records that a given face was manually unlinked from a given person, so
// FaceRecognitionService's automatic matching never reattaches that exact pair again — the face
// remains free to match a DIFFERENT person (or seed a brand-new one) going forward. Without this,
// unlinking a face is pointless: the next ~30s background recognition run would just see an
// unassigned face with the same embedding and relink it to the same person right away.
@Entity
@Table(name = "face_link_exclusions", uniqueConstraints = {
        @UniqueConstraint(name = "face_excluded_person_unique", columnNames = {"face_id", "excluded_person_id"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FaceLinkExclusionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "face_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private FaceEntity face;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "excluded_person_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private PersonEntity excludedPerson;

    private LocalDateTime createdAt;

    @PrePersist
    private void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
