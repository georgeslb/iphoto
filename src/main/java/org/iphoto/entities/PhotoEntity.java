package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "photos")
@Data
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@NoArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class PhotoEntity implements Persistable<UUID> {

    @Id
    @ToString.Include
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(nullable = false)
    @ToString.Include
    private String fileName;

    @Column(nullable = false, length = 1024)
    @ToString.Include
    private String originalPath;

    @Column(length = 1024)
    private String thumbnailPath;

    @Column(unique = true)
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
    private Boolean isFavorite = false;

    @Column(nullable = false)
    private Boolean isGarbage = false;
    
    private LocalDateTime garbageAt;
    private LocalDateTime createdAt;

    @ManyToMany(mappedBy = "photos", cascade = {CascadeType.DETACH, CascadeType.MERGE, CascadeType.PERSIST, CascadeType.REFRESH})
    private Set<AlbumEntity> albums = new HashSet<>();

    @Transient
    private boolean isNew = true;


    public PhotoEntity(UUID id, String fileName, String originalPath, String thumbnailPath, String fileHash, Long fileSize, String contentType) {
        this.id = id;
        this.fileName = fileName;
        this.originalPath = originalPath;
        this.thumbnailPath = thumbnailPath;
        this.fileHash = fileHash;
        this.fileSize = fileSize;
        this.contentType = contentType;
        this.isFavorite = false;
        this.isGarbage = false;
    }

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

}
