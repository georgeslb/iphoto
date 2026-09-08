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
@Table(name = "shares", uniqueConstraints = {
        @UniqueConstraint(name = "uk_shares_name_space_id", columnNames = { "name", "space_id"}),
        @UniqueConstraint(name = "uk_shares_external_source_external_id", columnNames = { "external_source", "external_id" })
}, indexes = {
        @Index(columnList = "space_id", name = "idx_shares_space_id"),
        @Index(columnList = "album_id", name = "idx_shares_album_id"),
        @Index(columnList = "user_md5,name", name = "idx_shares_user_md5_name")
})
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

    // Same externalSource/externalId idempotency pattern as AlbumEntity - lets a re-run of the
    // Immich album/share import find-or-create the same share instead of hitting the
    // name-collision throw in ShareService.createShare, or creating a duplicate.
    @Column(name = "external_source", length = 32)
    private String externalSource;

    @Column(name = "external_id", length = 128)
    private String externalId;

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