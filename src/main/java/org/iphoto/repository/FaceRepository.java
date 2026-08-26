package org.iphoto.repository;

import org.iphoto.configuration.MainSpaceLimited;
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
    @MainSpaceLimited
    Page<FaceEntity> findByPersonIsNull(Pageable pageable);

    @MainSpaceLimited
    Page<FaceEntity> findByPersonAndPhotoDateTakenBeforeOrderByPhotoDateTakenDesc(PersonEntity person, LocalDateTime dateTakenLowerRange, Pageable pageable);

    @MainSpaceLimited
    Page<FaceEntity> findByPersonAndPhotoDateTakenAfterOrderByPhotoDateTakenAsc(PersonEntity person, LocalDateTime dateTakenUpperRange, Pageable pageable);

    List<FaceEntity> findByPhotoId(UUID photoId);

    Page<FaceEntity> findByPersonId(UUID personId, Pageable pageable);

    List<FaceEntity> findByPersonIdAndPhotoId(UUID personId, UUID photoId);

    void deleteByPhotoId(PhotoEntity photo);
}