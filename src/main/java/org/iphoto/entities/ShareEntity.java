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

    // @OnDelete (DB-level ON DELETE CASCADE on the FK) is the correct direction: if the album
    // itself is deleted, this now-dangling share row goes with it. JPA cascade = REMOVE would be
    // the opposite and wrong — it would delete the ALBUM whenever this SHARE is deleted (one
    // album can have other shares pointing at it, or none at all; either way, deleting a share
    // link must never delete the album it points to). Deliberately no `cascade` attribute here.
    @ManyToOne
    @OnDelete(action = OnDeleteAction.CASCADE)
    AlbumEntity album;

    @Column(columnDefinition = "TEXT")
    private String qrcode;

    private String paswword;

    private LocalDateTime availableUntil;

    // Deterministic md5 of the owning user's technical id, computed once at creation time.
    // Lets a share URL (/share/{userMd5}/{name}) identify the owning user without exposing
    // their real UUID, and lets the DB look the share up directly without recomputing md5
    // for every user on every request.
    // Not marked non-nullable: ddl-auto=update adds this column to the existing `shares` table
    // without backfilling old rows, and a NOT NULL constraint would fail that migration.
    private String userMd5;

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