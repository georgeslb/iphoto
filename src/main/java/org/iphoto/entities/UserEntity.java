package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_username", columnNames = "username"))
@Data
@EqualsAndHashCode(callSuper = false)
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PUBLIC)
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@Setter(value = AccessLevel.PUBLIC)
@Getter
@ToString(onlyExplicitlyIncluded = true)
public class UserEntity implements Persistable<UUID>  {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @ToString.Include
    private UUID id;

    @ToString.Include
    private String username;
    private String password;
    private String role;

    // Per-user Immich connection (see service/immich/ImmichCredentialCipher and
    // service/UserService#resolveImmichCredentials): baseUrl is plain text (not sensitive), the
    // API key is AES-GCM encrypted using a key derived from this same user's `password` field
    // (the bcrypt hash, not the raw password - we never have the raw password except for the
    // instant it's submitted). This lets any thread that can load this row (including a
    // background SYNC_FROM_IMMICH/UPLOAD_FROM_IMMICH task with no HTTP session) decrypt it, at the cost of it
    // being obfuscation-at-rest rather than a real password-derived secret - see the cipher class
    // for the full rationale. UserService#resetPassword re-encrypts these two columns whenever
    // `password` changes, so they always stay decryptable with the row's current password hash.
    private String immichBaseUrl;
    private String immichApiKeyEncrypted;
    private String immichApiKeyIv;

    @Builder.Default
    @Column(name = "duplication_regrouping", nullable = false)
    private Boolean duplicationRegrouping = true;

    @OneToMany(mappedBy = "user", fetch = FetchType.EAGER, cascade = CascadeType.REMOVE)
    Set<SpaceEntity> spaces;

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