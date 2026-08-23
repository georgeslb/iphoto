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
import java.util.UUID;

@Entity
@Table(name = "shares", uniqueConstraints = {@UniqueConstraint(name = "name_space_unique_shares", columnNames = { "name", "space_id"})})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true) // Changed callSuper to true
@Getter
@Setter
@SuperBuilder
@ToString(onlyExplicitlyIncluded = true)
public class ShareEntity extends BaseEntity { // Extends BaseEntity

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    @EqualsAndHashCode.Include
    private UUID id;


    @Column(nullable = false)
    @ToString.Include
    private String name;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @ManyToOne(cascade = CascadeType.REMOVE)
    @OnDelete(action = OnDeleteAction.CASCADE)
    AlbumEntity album;

    @Column(columnDefinition = "TEXT")
    private String qrcode;

    private String paswword;

    private LocalDateTime availableUntil;

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

    public ShareEntity() {
        super();
    }
}