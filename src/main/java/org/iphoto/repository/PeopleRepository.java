package org.iphoto.repository;

import org.apache.commons.io.function.IOStream;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.UserEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PeopleRepository extends JpaRepository<PersonEntity, UUID> {
    Page<PersonEntity> findByUser(UserEntity user, Pageable pageable);

    Optional<PersonEntity> findByIdAndUser(UUID id, UserEntity user);
}
