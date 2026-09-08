package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.UUID;

@EqualsAndHashCode(callSuper = true)
@Entity
// uk_people_cover_face_id matches this @OneToOne cover_face_id's implicit uniqueness (V1's
// ground truth - see V1__baseline_schema.sql) rather than leaving it unnamed.
@Table(name = "people",
        uniqueConstraints = {@UniqueConstraint(name = "uk_people_cover_face_id", columnNames = "cover_face_id")},
        indexes = {@Index(columnList = "space_id", name = "idx_people_space_id")})
@Getter
@Setter
@SuperBuilder
public class PersonEntity extends BaseEntity { // Extends BaseEntity

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    // Removed user field as it's inherited from BaseEntity

    @Column(nullable = true) // Name can be assigned by the user later
    private String name;

    // A person can be linked to many faces.
    // 'mappedBy = "person"' indicates that the 'person' field in FaceEntity owns the relationship.
    // CascadeType is intentionally removed (or specifically tailored) so that deleting a Person
    // does NOT delete the associated Faces.
    // PreRemove callback will nullify the person reference in FaceEntity before the Person is deleted.
    @OneToMany(
        mappedBy = "person",
        fetch = FetchType.LAZY
    )
    private List<FaceEntity> faces;

    // Precomputed per-year running-average embeddings (see PersonEmbeddingBucketEntity/
    // PersonRecognitionService#addFaceToBucket) - pure derived cache data, nothing else
    // references it, so unlike `faces` above it's fine (correct, even) to cascade-delete these
    // along with the person instead of nulling out a reference.
    @OneToMany(
        mappedBy = "person",
        fetch = FetchType.LAZY,
        cascade = CascadeType.ALL,
        orphanRemoval = true
    )
    private List<PersonEmbeddingBucketEntity> embeddingBuckets;

    // The face that represents this person in the UI (their "profile picture").
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cover_face_id")
    private FaceEntity coverFace;

    // Indicates whether the cover face was chosen by the system or explicitly by the user.
    @Enumerated(EnumType.STRING)
    @Column(name = "cover_face_origin")
    private CoverFaceOrigin coverFaceOrigin;

    // Distinct photos reachable through this person's faces - persisted and incrementally
    // maintained (recompute-on-write, see PersonRecognitionService.recalculatePhotoCount) the same
    // way AlbumEntity.photoCount is, rather than computed per-request, so PersonRepository's
    // Persons-page sort query has a real queryable column to ORDER BY. @Builder.Default rather than
    // requiring every builder call site (production and test) to set it explicitly - a
    // freshly-built person always starts at 0 faces/photos unless a caller says otherwise.
    @Builder.Default
    @Column(nullable = false)
    private Integer photoCount = 0;

    // This ensures that before a Person is deleted from the DB, the reference in all
    // their associated faces is set to null, preventing a foreign key constraint violation
    // while keeping the face records intact.
    @PreRemove
    private void preRemove() {
        for (FaceEntity face : faces) {
            face.setPerson(null);
        }
    }

    // BaseEntity's Persistable<UUID>.isNew() reads this transient flag (default true). Every
    // other BaseEntity subclass (ShareEntity, AlbumEntity, SpaceEntity) flips it to false via
    // these same two callbacks — PersonEntity never did, so isNew() stayed true forever, even
    // for a person freshly loaded from the DB. That made personRepository.save() always call
    // persist() instead of merge(): fine for a genuinely new person, but
    // InvalidDataAccessApiUsageException ("detached entity passed to persist") the moment an
    // *existing* person — loaded in one transaction, re-saved in another, e.g.
    // PersonRecognitionService.updatePerson, which is @Transactional(REQUIRES_NEW) — got saved
    // again.
    @PrePersist
    private void onCreate() {
        isNew = false;
    }

    @PostLoad
    private void markNotNew() {
        this.isNew = false;
    }

    public PersonEntity() {
        super();
    }
}