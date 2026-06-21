package org.iphoto.repository;

import org.apache.commons.io.function.IOStream;
import org.iphoto.entities.FaceEntity;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PhotoEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface FaceRepository extends JpaRepository<FaceEntity, UUID> {
    Page<FaceEntity> findByPersonIsNull(Pageable pageable);

    Page<FaceEntity> findByPersonAndPhotoDateTakenBeforeOrderByPhotoDateTakenDesc(PersonEntity person, LocalDateTime dateTakenLowerRange, Pageable pageable);

    List<FaceEntity> findAllByPhotoId(UUID photoId);

    Page<FaceEntity> findByPersonId(UUID id, Pageable pageable);
}