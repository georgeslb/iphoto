package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static jakarta.persistence.EnumType.STRING;

@Entity
@Table(name = "spaces", uniqueConstraints = {@UniqueConstraint(name = "uk_spaces_name_user_id", columnNames = { "name", "user_id"})},
        indexes = {@Index(columnList = "user_id", name = "idx_spaces_user_id")})
@EqualsAndHashCode(onlyExplicitlyIncluded = true) // Changed callSuper to true
@SuperBuilder
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
public final class SpaceEntity  implements Persistable<UUID> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(nullable = false)
    @ToString.Include
    private String name;

    @Column(columnDefinition = "TEXT")
    @Enumerated(STRING)
    private SpaceType type;


    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Include
    private UserEntity user;

    @Column()
    @ToString.Include
    private volatile Integer photoCount;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Column()
    private Boolean isGarbage;

    // Block/unblock-on-sync feature: non-null while this Space is actively mirrored to an
    // on-device folder by the Android app (see SyncScreen/SyncViewModel), pointing at the exact
    // device token that owns the lock. SpaceService#assertSyncWritable rejects any write not
    // authenticated as that same device - see its own comment and DeviceTokenAuthenticationFilter.
    // Cleared on manual unlock (either that same device, or a web-session request from the
    // space's own owner - the escape hatch for a lost/uninstalled device) and automatically when
    // the device token itself is revoked (SpaceSyncLockListener).
    // No @OnDelete here - the FK's ON DELETE SET NULL is hand-written directly in the V7 migration
    // (Hibernate's @OnDelete annotation only supports generating CASCADE, not SET NULL, and this
    // codebase hand-writes its DDL via Flyway rather than letting Hibernate generate any schema).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sync_locked_by_device_id")
    private DeviceTokenEntity syncLockedByDevice;

    @Transient
    private boolean isNew = true;

    @OneToMany(mappedBy = "space", fetch = FetchType.LAZY, cascade = CascadeType.REMOVE)
    @OnDelete(action = OnDeleteAction.CASCADE)
    Set<PhotoEntity> photos;

    @OneToMany(mappedBy = "space", fetch = FetchType.LAZY, cascade = CascadeType.REMOVE)
    @OnDelete(action = OnDeleteAction.CASCADE)
    Set<AlbumEntity> albums;

    @OneToMany(mappedBy = "space", fetch = FetchType.LAZY, cascade = CascadeType.REMOVE)
    @OnDelete(action = OnDeleteAction.CASCADE)
    Set<DuplicateEntity> duplicates;

    @OneToMany(mappedBy = "space", fetch = FetchType.LAZY, cascade = CascadeType.REMOVE)
    @OnDelete(action = OnDeleteAction.CASCADE)
    Set<PersonEntity> persons;

    @OneToMany(mappedBy = "space", fetch = FetchType.LAZY, cascade = CascadeType.REMOVE)
    @OnDelete(action = OnDeleteAction.CASCADE)
    Set<ShareEntity> shares;


    @PrePersist
    private void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        isNew = false;
    }

    @PreUpdate
    private void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public SpaceEntity() {
        super();
    }

    @Override
    public boolean isNew() {
        return isNew || id == null;
    }
}