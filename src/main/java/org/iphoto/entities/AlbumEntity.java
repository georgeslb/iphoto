package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "albums")
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true) // Changed callSuper to true
@SuperBuilder
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
public final class AlbumEntity extends BaseEntity { // Extends BaseEntity
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(nullable = false, unique = true) // This unique constraint might need to be adjusted to be unique per user
    @ToString.Include
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cover_photo_id")
    @ToString.Include
    private PhotoEntity coverPhoto;

    @Column()
    @ToString.Include
    private Integer photoCount;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "album_photos",
        joinColumns = @JoinColumn(name = "album_id"),
        inverseJoinColumns = @JoinColumn(name = "photo_id")
    )
    private Set<PhotoEntity> photos;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @OneToMany(fetch = FetchType.LAZY)
    Set<ShareEntity> shares;

    @Override
    public UUID getId() {
        return id;
    }


    public void addPhoto(PhotoEntity photo) {
        this.photos.add(photo);
        // If the relationship is bidirectional, you would also set the other side here.
    }

    public void removePhoto(PhotoEntity photo) {
        this.photos.remove(photo);
        // If the relationship is bidirectional, you would also nullify the other side here.
    }


    @PrePersist
    private void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        isNew = false;
    }

    @PreUpdate
    private void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public AlbumEntity() {
        super();
    }
}