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
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

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

    public FaceEntity(PhotoEntity photo, int x, int y, int width, int height) {
        this.photo = photo;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }
}