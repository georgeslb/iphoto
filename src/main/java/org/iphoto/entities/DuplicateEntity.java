package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.iphoto.model.DuplicateStatus;

import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "duplicates")
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

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "duplicate_photos",
            joinColumns = @JoinColumn(name = "duplicate_id"),
            inverseJoinColumns = @JoinColumn(name = "photo_id")
    )
    private Set<PhotoEntity> photos;

    @Enumerated(EnumType.STRING)
    private DuplicateStatus status;

    @Column(unique = true)
    private String hash;

}