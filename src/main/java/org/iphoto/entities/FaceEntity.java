package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "faces", indexes = {
        @Index(columnList = "photo_id", name = "idx_faces_photo_id"),
        @Index(columnList = "person_id", name = "idx_faces_person_id")
})
@Getter
@Setter
@SuperBuilder
public class FaceEntity { // Extends BaseEntity

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "photo_id", nullable = false)
    private PhotoEntity photo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_id")
    private PersonEntity person;

    @Column(nullable = false)
    private int x;

    @Column(nullable = false)
    private int y;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(columnDefinition = "BYTEA")
    private byte[] embedding; // Stores the 128-dimensional float array for recognition

    @Column(columnDefinition = "BYTEA")
    private byte[] faceThumbnail; // Stores the cropped JPEG image of the face for UI display


    public FaceEntity() {
        super();
    }
}