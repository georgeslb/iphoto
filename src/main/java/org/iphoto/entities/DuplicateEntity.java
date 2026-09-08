package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "duplicates",
        uniqueConstraints = {@UniqueConstraint(name = "uk_duplicates_hash", columnNames = "hash")},
        indexes = {@Index(columnList = "space_id", name = "idx_duplicates_space_id")})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
@Getter
@Setter
@SuperBuilder
public class DuplicateEntity extends BaseEntity {

    @Id
    @ToString.Include
    @EqualsAndHashCode.Include
    private UUID id;

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "duplicate", cascade = {CascadeType.ALL})
    private Set<PhotoDuplicateEntity> photoDuplicates;

    // Uniqueness enforced by uk_duplicates_hash above (named explicitly, matching V1's
    // convention) rather than @Column(unique = true), which would leave Hibernate to pick its
    // own unnamed constraint name.
    private String hash;

    public DuplicateEntity() {
        super();
    }

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