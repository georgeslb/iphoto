package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Entity
@Table(name = "photos", uniqueConstraints = {@UniqueConstraint(name = "uk_photos_file_hash_user_id", columnNames = { "file_hash", "user_id"})}
, indexes = {
        @Index(columnList = "space_id", name = "idx_photos_space_id"),
        @Index(columnList = "space_id,is_garbage", name = "idx_photos_space_id_is_garbage"),
        @Index(columnList = "space_id,grouped_day", name = "idx_photos_space_id_grouped_day"),
        @Index(columnList = "user_id", name = "idx_photos_user_id"),
        @Index(columnList = "space_id,date_taken", name = "idx_photos_space_id_date_taken")
})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
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

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Include
    private UserEntity user;

    @Column(length = 1024)
    private String thumbnailPath;

    @Column(nullable = false)
    @ToString.Include
    @EqualsAndHashCode.Include
    private String fileHash;
    
    private Long fileSize;
    private String contentType;
    @ToString.Include
    private LocalDateTime dateTaken;

    // Derived from dateTaken (date part only), kept in sync via onCreate/onUpdate below rather
    // than a separate persisted day-index table - lets day-grouped queries (day-index browsing)
    // use a plain indexed column instead of grouping by extract(year/month/day from date_taken)
    // live on every request.
    @Column(name = "grouped_day")
    private LocalDate groupedDay;

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

    // Raw ORB descriptor matrix (row-major, ORB_DESCRIPTOR_COLS bytes per keypoint) computed once
    // at upload time, so duplicate confirmation doesn't need to reload the image and re-run ORB
    // detection for every candidate pair. See DuplicationService.
    @Column(columnDefinition = "BYTEA")
    private byte[] orbDescriptors;

    // Keypoint (x, y) pairs as 4-byte floats, index-aligned with orbDescriptors' rows.
    @Column(columnDefinition = "BYTEA")
    private byte[] orbKeypoints;

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
        syncGroupedDay();
    }

    @PreUpdate
    protected void onUpdate() {
        syncGroupedDay();
    }

    private void syncGroupedDay() {
        this.groupedDay = dateTaken != null ? dateTaken.toLocalDate() : null;
    }

    public PhotoEntity() {
        super();
    }

}