package org.iphoto.model;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "photos")
public class PhotoEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false, length = 1024)
    private String originalPath;

    @Column(length = 1024)
    private String thumbnailPath;

    @Column(unique = true)
    private String fileHash;
    
    private Long fileSize;
    private String contentType;
    private LocalDateTime dateTaken;
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

    @Transient
    private boolean isNew = true;

    public PhotoEntity() {
        this.isFavorite = false;
        this.isGarbage = false;
    }

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
        this.isNew = true;
    }

    @Override
    public boolean isNew() {
        return isNew || id == null;
    }

    public void setNew(boolean isNew) {
        this.isNew = isNew;
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

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getOriginalPath() { return originalPath; }
    public void setOriginalPath(String originalPath) { this.originalPath = originalPath; }
    public String getThumbnailPath() { return thumbnailPath; }
    public void setThumbnailPath(String thumbnailPath) { this.thumbnailPath = thumbnailPath; }
    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public LocalDateTime getDateTaken() { return dateTaken; }
    public void setDateTaken(LocalDateTime dateTaken) { this.dateTaken = dateTaken; }
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
    public Long getDuration() { return duration; }
    public void setDuration(Long duration) { this.duration = duration; }
    public String getCameraModel() { return cameraModel; }
    public void setCameraModel(String cameraModel) { this.cameraModel = cameraModel; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public Boolean getIsFavorite() { return isFavorite != null && isFavorite; }
    public void setIsFavorite(Boolean isFavorite) { this.isFavorite = isFavorite; }
    public Boolean getIsGarbage() { return isGarbage != null && isGarbage; }
    public void setIsGarbage(Boolean isGarbage) { this.isGarbage = isGarbage; }
    public LocalDateTime getGarbageAt() { return garbageAt; }
    public void setGarbageAt(LocalDateTime garbageAt) { this.garbageAt = garbageAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
