package org.iphoto.repository;

import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.UserEntity; // Import UserEntity
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PersonRepository extends JpaRepository<PersonEntity, UUID> {
    List<PersonEntity> findAllByUser(UserEntity user);
    Optional<PersonEntity> findByIdAndUser(UUID id, UserEntity user);

    List<PersonEntity>  findByCoverFace(FaceEntity faceEntity);
}