package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.*;
import org.iphoto.model.DuplicateStatus;
import org.springframework.data.domain.Persistable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "duplicates")
@Data
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
@NoArgsConstructor
@AllArgsConstructor
@ToString(onlyExplicitlyIncluded = true)
@Builder
public class DuplicateEntity implements Persistable<UUID> {

    @Id
    @ToString.Include
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

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

    @Transient
    private boolean isNew = true;

}