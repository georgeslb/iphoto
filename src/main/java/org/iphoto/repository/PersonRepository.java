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

    // Backs the Person page/Persons grid rename-collision flow (FaceService.renamePerson) — every
    // OTHER person already sharing the name being renamed to (excludes the person being renamed
    // itself, which trivially "collides" with its own current name on a no-op rename). Plural
    // (unlike findByNameIgnoreCase above, used by the single-face rename flow) because this app
    // doesn't actually enforce name uniqueness at the DB level, so more than one existing person
    // can legitimately already share a name by the time a rename runs into it.
    @MainSpaceLimited
    List<PersonEntity> findByNameIgnoreCaseAndIdNot(String name, UUID excludeId);
}