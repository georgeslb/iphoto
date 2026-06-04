package org.iphoto.repository;

import org.iphoto.entities.SpaceEntity;
import org.iphoto.entities.SpaceType;
import org.iphoto.entities.UserEntity;
import org.iphoto.exception.NoMatchUserSpaceException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface SpaceRepository extends JpaRepository<SpaceEntity, UUID> {
    Set<SpaceEntity> findByUser(UserEntity user);

    Optional<SpaceEntity> findByIdAndUser(UUID id, UserEntity user);

    default SpaceEntity findByTypeEqualsMainAndUser(UserEntity user) {
        return user.getSpaces().stream().filter(spaceEntity -> SpaceType.MAIN.equals(spaceEntity.getType())).findFirst().orElseThrow(NoMatchUserSpaceException::new);
    }
}
