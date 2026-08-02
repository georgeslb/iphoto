package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.*;

@Entity
@Table(name = "photos")
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true) // Changed callSuper to true
@ToString(onlyExplicitlyIncluded = true)
@Getter
@Setter
@SuperBuilder
public class PhotoEntity extends BaseEntity  { // Extends BaseEntity

    @Id
    @ToString.Include
    private UUID id;

    // Removed user field as it's inherited from BaseEntity

    @Column(nullable = false)
    @ToString.Include
    private String fileName;

    @Column(nullable = false, length = 1024)
    @ToString.Include
    private String originalPath;

    @Column(length = 1024)
    private String thumbnailPath;

    @Column(unique = true) // This unique constraint might need to be adjusted to be unique per user
    @ToString.Include
    @EqualsAndHashCode.Include
    private String fileHash;
    
    private Long fileSize;
    private String contentType;
    @ToString.Include
    private LocalDateTime dateTaken;
    private LocalDateTime fileSystemCreationDate; // New field for fallback
    private Integer width;
    private Integer height;
    private Long duration;
    private String cameraModel;
    private String location;

    @Column(nullable = false)
    private Boolean isFavorite;

    @Column(nullable = false)
    private Boolean isGarbage;

    @Column(columnDefinition = "BYTEA")
    private byte[] phash;

    @Column(columnDefinition = "BYTEA")
    private byte[] mhash;

    private LocalDateTime garbageAt;
    private LocalDateTime createdAt;

    @OneToOne(cascade = CascadeType.ALL, mappedBy = "photo")
    @OnDelete(action = OnDeleteAction.CASCADE)
    PhotoMetadataEntity photoMetadataEntity;

    @ManyToMany(mappedBy = "photos", cascade = {CascadeType.DETACH, CascadeType.MERGE, CascadeType.PERSIST, CascadeType.REFRESH})
    private Set<AlbumEntity> albums = new HashSet<>();

    // This ensures that when a Photo is deleted, all Faces detected within it are also deleted.
    @OneToMany(
        mappedBy = "photo",
        cascade = CascadeType.ALL,
        orphanRemoval = true,
        fetch = FetchType.LAZY
    )
    @OnDelete(action = OnDeleteAction.CASCADE)
    private List<FaceEntity> faces = new ArrayList<>();


    @Override
    public boolean isNew() {
        return isNew || id == null;
    }

    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @PrePersist
    protected void onCreate() {
        this.isNew = false;
        createdAt = LocalDateTime.now();
        if (isFavorite == null) isFavorite = false;
        if (isGarbage == null) isGarbage = false;
    }

    public PhotoEntity() {
        super();
    }

}