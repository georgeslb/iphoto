package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.springframework.data.domain.Persistable;

import java.util.UUID;

@MappedSuperclass
@Getter
@Setter
@SuperBuilder
public abstract class BaseEntity implements Persistable<UUID> {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    protected UserEntity user;

    public BaseEntity() {

    }

    @Transient
    protected boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }


}
