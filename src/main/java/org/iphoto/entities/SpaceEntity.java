package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.UUID;

import static jakarta.persistence.EnumType.STRING;

@Entity
@Table(name = "spaces", uniqueConstraints = {@UniqueConstraint(name = "name_user_unique", columnNames = "name,user_id")})
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


    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Include
    private UserEntity user;

    @Column()
    @ToString.Include
    private Integer photoCount;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;


    @Transient
    private boolean isNew = true;


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