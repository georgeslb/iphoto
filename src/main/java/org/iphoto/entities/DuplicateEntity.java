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
@Table(name = "duplicates", indexes = {@Index(columnList = "hash", name = "idx_hash_duplicates")})
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

    @Column(unique = true)
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