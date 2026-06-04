package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.LocalDateTime;

@Entity
@Table(name = "people")
@Data
@NoArgsConstructor
public class PersonEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

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
    private List<FaceEntity> faces = new ArrayList<>();

    // The face that represents this person in the UI (their "profile picture").
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cover_face_id")
    private FaceEntity coverFace;

    // Indicates whether the cover face was chosen by the system or explicitly by the user.
    @Enumerated(EnumType.STRING)
    @Column(name = "cover_face_origin")
    private CoverFaceOrigin coverFaceOrigin;

    public PersonEntity(String name) {
        this.name = name;
    }

    // This ensures that before a Person is deleted from the DB, the reference in all
    // their associated faces is set to null, preventing a foreign key constraint violation
    // while keeping the face records intact.
    @PreRemove
    private void preRemove() {
        for (FaceEntity face : faces) {
            face.setPerson(null);
        }
    }


}