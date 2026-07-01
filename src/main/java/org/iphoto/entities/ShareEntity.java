package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "shares")
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true) // Changed callSuper to true
@Getter
@Setter
@SuperBuilder
@ToString(onlyExplicitlyIncluded = true)
public class ShareEntity extends BaseEntity implements Persistable<UUID> { // Extends BaseEntity

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    @EqualsAndHashCode.Include
    private UUID id;

    // Removed user field as it's inherited from BaseEntity

    @Column(nullable = false, unique = true)
    @ToString.Include
    private String name;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Transient
    private boolean isNew = true;

    @ManyToOne
    AlbumEntity album;

    @Column(columnDefinition = "TEXT")
    private String qrcode;

    private String paswword;

    private LocalDateTime availableUntil;


    @Override
    public boolean isNew() {
        return isNew;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        isNew = false;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}