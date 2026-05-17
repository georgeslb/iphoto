package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "shares")
@Data
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@NoArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
public class ShareEntity implements Persistable<UUID> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    @EqualsAndHashCode.Include
    private UUID id;

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
