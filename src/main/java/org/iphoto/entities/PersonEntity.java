package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.UUID;

@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "people")
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

    // The face that represents this person in the UI (their "profile picture").
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cover_face_id")
    private FaceEntity coverFace;

    // Indicates whether the cover face was chosen by the system or explicitly by the user.
    @Enumerated(EnumType.STRING)
    @Column(name = "cover_face_origin")
    private CoverFaceOrigin coverFaceOrigin;

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