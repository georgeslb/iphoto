package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.iphoto.model.DuplicateStatus;
import org.springframework.data.domain.Persistable;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "duplicate_photos", uniqueConstraints = {@UniqueConstraint(name = "photo_album_unique", columnNames = { "photo_id", "duplicate_id"})}
        , indexes = {@Index(columnList = "photo_id", name = "photo_index"), @Index(columnList = "duplicate_id", name = "duplicate_index")})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PhotoDuplicateEntity implements Persistable<UUID> {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "photo_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @EqualsAndHashCode.Include
    @ToString.Include
    protected PhotoEntity photo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "duplicate_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @EqualsAndHashCode.Include
    @ToString.Include
    protected DuplicateEntity duplicate;

    @Enumerated(EnumType.STRING)
    private DuplicateStatus status;

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

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        PhotoDuplicateEntity that = (PhotoDuplicateEntity) o;
        return Objects.equals(photo, that.photo) && Objects.equals(duplicate, that.duplicate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(photo, duplicate);
    }

    @Override
    public String toString() {
        return "PhotoDuplicateEntity{" +
                "photo=" + photo.getId() +
                ", duplicate=" + duplicate.getId() +
                ", status=" + status +
                '}';
    }
}
