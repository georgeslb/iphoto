package org.iphoto.repository;

import org.iphoto.entities.PersonEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PeopleRepository extends JpaRepository<PersonEntity, UUID> {
}
