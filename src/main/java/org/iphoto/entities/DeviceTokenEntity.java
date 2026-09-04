package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.UUID;

// Long-lived mobile auth (see service/auth/DeviceTokenService) - one row per device/login. Only
// tokenHash is ever stored; the raw token is returned to the client exactly once, at login, and
// never persisted or logged. revokedAt null = active; soft-revoke (not delete) so a device's
// history stays visible in "manage devices" rather than just disappearing.
@Entity
@Table(name = "device_tokens")
@Data
@EqualsAndHashCode(callSuper = false)
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PUBLIC)
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@Setter(value = AccessLevel.PUBLIC)
@Getter
@ToString(onlyExplicitlyIncluded = true)
public class DeviceTokenEntity implements Persistable<UUID> {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    private UUID id;

    // LAZY (unlike UserEntity.spaces' EAGER) - this row is looked up per-request by tokenHash, no
    // need to drag in the user graph unless the caller actually asks for it.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(nullable = false, unique = true)
    private String tokenHash;

    @ToString.Include
    private String deviceName;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime lastUsedAt;

    private LocalDateTime revokedAt;

    @Transient
    protected boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew || id == null;
    }

    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @PrePersist
    protected void onCreate() {
        this.isNew = false;
    }
}
