package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "faces")
@Getter
@Setter
@SuperBuilder
public class FaceEntity extends BaseEntity { // Extends BaseEntity

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    // Removed user field as it's inherited from BaseEntity

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
}