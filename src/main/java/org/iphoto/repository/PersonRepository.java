package org.iphoto.repository;

import org.iphoto.configuration.MainSpaceLimited;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PersonEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PersonRepository extends JpaRepository<PersonEntity, UUID> {
    @MainSpaceLimited
    List<PersonEntity> findAll();
    List<PersonEntity>  findByCoverFace(FaceEntity faceEntity);

    // Scoped to the current user's space like every other @MainSpaceLimited lookup here — a name
    // match should only ever consider the current user's own people.
    @MainSpaceLimited
    Optional<PersonEntity> findByNameIgnoreCase(String name);
}