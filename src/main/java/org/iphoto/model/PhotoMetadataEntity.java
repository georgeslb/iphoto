package org.iphoto.model;

import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;
import java.util.UUID;

@Entity
@Table(name = "photo_metadata")
public class PhotoMetadataEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @OneToOne
    @MapsId
    @JoinColumn(name = "id")
    private PhotoEntity photo;

    @Column(columnDefinition = "TEXT")
    private String rawMetadataJson;

    private String aperture;
    private String shutterSpeed;
    private String iso;
    private String focalLength;
    private String lensModel;
    private Double latitude;
    private Double longitude;
    private String codec;
    private String bitRate;
    private String frameRate;

    @Transient
    private boolean isNew = true;

    public PhotoMetadataEntity() {}

    public PhotoMetadataEntity(PhotoEntity photo) {
        this.photo = photo;
        this.id = photo.getId();
        this.isNew = true;
    }

    @Override
    public boolean isNew() {
        return isNew || id == null;
    }

    @PrePersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public PhotoEntity getPhoto() { return photo; }
    public void setPhoto(PhotoEntity photo) { this.photo = photo; }
    public String getRawMetadataJson() { return rawMetadataJson; }
    public void setRawMetadataJson(String rawMetadataJson) { this.rawMetadataJson = rawMetadataJson; }
    public String getAperture() { return aperture; }
    public void setAperture(String aperture) { this.aperture = aperture; }
    public String getShutterSpeed() { return shutterSpeed; }
    public void setShutterSpeed(String shutterSpeed) { this.shutterSpeed = shutterSpeed; }
    public String getIso() { return iso; }
    public void setIso(String iso) { this.iso = iso; }
    public String getFocalLength() { return focalLength; }
    public void setFocalLength(String focalLength) { this.focalLength = focalLength; }
    public String getLensModel() { return lensModel; }
    public void setLensModel(String lensModel) { this.lensModel = lensModel; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public String getCodec() { return codec; }
    public void setCodec(String codec) { this.codec = codec; }
    public String getBitRate() { return bitRate; }
    public void setBitRate(String bitRate) { this.bitRate = bitRate; }
    public String getFrameRate() { return frameRate; }
    public void setFrameRate(String frameRate) { this.frameRate = frameRate; }
}
