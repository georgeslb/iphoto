package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.iphoto.model.ImmichImportDayStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

// One row per (user, calendar day) that has at least one asset on that user's own Immich server -
// the "table of days where we have photos on Immich" the Immich-import page is built around. Each
// user connects their own Immich server (see UserEntity.immichBaseUrl/immichApiKeyEncrypted), so
// this indexes per-user, not a single shared external server configuration.
@Entity
@Table(name = "immich_import_days",
        uniqueConstraints = @UniqueConstraint(name = "uk_immich_import_days_day_user_id", columnNames = {"day", "user_id"}),
        indexes = @Index(columnList = "user_id,day", name = "idx_immich_import_days_user_id_day"))
@Data
@EqualsAndHashCode(callSuper = false)
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PUBLIC)
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class ImmichImportDayEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserEntity user;

    @Column(nullable = false)
    private LocalDate day;

    // Refreshed from Immich's /timeline/buckets on every "sync days" call.
    private Integer immichAssetCount;

    // How many assets were actually queued (as SYNC_FROM_IMMICH tasks) the last time this day's import
    // was triggered - the denominator against which importedCount/skippedCount/failedCount are
    // compared to decide when the day has reached a terminal status.
    private Integer queuedCount;

    @Builder.Default
    private Integer importedCount = 0;

    @Builder.Default
    private Integer skippedCount = 0;

    @Builder.Default
    private Integer failedCount = 0;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ImmichImportDayStatus status = ImmichImportDayStatus.NOT_IMPORTED;

    private LocalDateTime lastAttemptedAt;
    private LocalDateTime lastSyncedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
