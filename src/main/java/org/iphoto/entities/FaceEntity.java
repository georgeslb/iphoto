package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "faces")
@Data
@NoArgsConstructor
public class FaceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "photo_id", nullable = false)
    private PhotoEntity photo;

    // A face belongs to exactly one person. This can be null initially if a face is detected but not yet recognized/clustered.
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
    private byte[] embedding; // Stores the 128-dimensional float array

    public FaceEntity(PhotoEntity photo, int x, int y, int width, int height) {
        this.photo = photo;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

}