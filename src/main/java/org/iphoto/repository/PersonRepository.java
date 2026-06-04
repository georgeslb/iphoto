package org.iphoto.repository;

import org.iphoto.configuration.MainSpaceLimited;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PersonEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PersonRepository extends JpaRepository<PersonEntity, UUID> {
    @MainSpaceLimited
    List<PersonEntity> findAll();
    List<PersonEntity>  findByCoverFace(FaceEntity faceEntity);
}