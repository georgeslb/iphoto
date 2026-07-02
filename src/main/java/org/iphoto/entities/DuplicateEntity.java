package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.iphoto.model.DuplicateStatus;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "duplicates")
@Getter
@Setter
@SuperBuilder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true) // Changed callSuper to true
@ToString(onlyExplicitlyIncluded = true)
public class DuplicateEntity extends BaseEntity { // Extends BaseEntity

    @Id
    @ToString.Include
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "duplicate_photos",
            joinColumns = @JoinColumn(name = "duplicate_id"),
            inverseJoinColumns = @JoinColumn(name = "photo_id")
    )
    private Set<PhotoEntity> photos = new HashSet<>();

    @Enumerated(EnumType.STRING)
    private DuplicateStatus status;

    @Column(unique = true) // This unique constraint might need to be adjusted to be unique per user
    private String hash;


    public DuplicateEntity() {
        super();
    }


}