package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;
import java.util.UUID;

@Entity
@Table(name = "photo_metadata")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class PhotoMetadataEntity implements Persistable<UUID> {

    @Id
    @EqualsAndHashCode.Include
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


    public PhotoMetadataEntity(PhotoEntity photo) {
        this.photo = photo;
        this.id = photo.getId();
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

}
