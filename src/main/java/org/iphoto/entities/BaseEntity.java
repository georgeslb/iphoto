package org.iphoto.entities;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.springframework.data.domain.Persistable;

import java.util.UUID;

@MappedSuperclass
@Getter
@Setter
@SuperBuilder
// 1. Define the filter name and parameter type
@FilterDef(
        name = "spaceFilter",
        parameters = @ParamDef(name = "currentSpaceId", type = UUID.class)
)
// 2. Define the WHERE clause appended to database queries
@Filter(
        name = "spaceFilter",
        condition = "space_id= :currentSpaceId"
)
public abstract class BaseEntity implements Persistable<UUID> {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "space_id", nullable = false)
    protected SpaceEntity space;

    public BaseEntity() {

    }

    @Transient
    protected boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }


}
