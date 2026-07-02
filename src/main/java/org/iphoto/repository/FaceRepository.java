package org.iphoto.repository;

import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.UserEntity; // Import UserEntity
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FaceRepository extends JpaRepository<FaceEntity, UUID> {
    Page<FaceEntity> findByUserAndPersonIsNull(UserEntity user, Pageable pageable);

    Page<FaceEntity> findByUserAndPersonAndPhotoDateTakenBeforeOrderByPhotoDateTakenDesc(UserEntity user, PersonEntity person, LocalDateTime dateTakenLowerRange, Pageable pageable);

    List<FaceEntity> findAllByUserAndPhotoId(UserEntity user, UUID photoId);

    Page<FaceEntity> findByUserAndPersonId(UserEntity user, UUID id, Pageable pageable);

    Optional<FaceEntity> findByIdAndUser(UUID id, UserEntity user);

    void deleteByPhotoId(PhotoEntity photo);
}