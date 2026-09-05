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
@Table(name = "albums", uniqueConstraints = {
        @UniqueConstraint(name = "name_space_unique_albums", columnNames = { "name", "space_id"}),
        @UniqueConstraint(name = "uk_albums_external", columnNames = { "external_source", "external_id" })
})
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

    @Column(nullable = false) // This unique constraint might need to be adjusted to be unique per user
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

    @ManyToMany
    @JoinTable(
        name = "album_photos",
        joinColumns = @JoinColumn(name = "album_id"),
        inverseJoinColumns = @JoinColumn(name = "photo_id"),
        indexes = {@Index(columnList = "photo_id",name =  "idx_photo_id_albums")}
    )
    private Set<PhotoEntity> photos;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Set only for albums recreated from an external system (e.g. an Immich album import) - lets
    // a re-run find-or-create the same album instead of hitting the name-collision throw in
    // AlbumService.createAlbum, or creating a duplicate. Null/null for every ordinary album;
    // Postgres treats each NULL pair as distinct, so the unique constraint above never fires for
    // non-imported albums. e.g. externalSource="IMMICH", externalId=<immich album uuid>.
    @Column(name = "external_source", length = 32)
    private String externalSource;

    @Column(name = "external_id", length = 128)
    private String externalId;

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