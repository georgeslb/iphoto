package org.iphoto.repository;

import org.iphoto.entities.AlbumEntity;
import org.iphoto.entities.PersonEntity;
import org.iphoto.entities.PhotoEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface PhotoRepository extends JpaRepository<PhotoEntity, UUID> {

    Page<PhotoEntity> findBySpaceIdAndIsGarbageFalse(UUID spaceId, Pageable pageable);
    
    Page<PhotoEntity> findBySpaceIdInAndIsGarbageTrue(Set<UUID> spaceIds, Pageable pageable);


    Page<PhotoEntity> findByIsGarbageFalseAndAlbums(AlbumEntity album, Pageable pageable);

    Page<PhotoEntity> findByFacesPerson(PersonEntity person, Pageable pageable);

    long countByIsGarbageFalseAndAlbums(AlbumEntity album);

    Slice<PhotoEntity> findBySpaceIdAndDateTakenBetweenAndPhashNotNullAndMhashNotNullAndIsGarbageFalse(UUID spaceId, LocalDateTime startDateTaken, LocalDateTime endDateTaken, Pageable pageable);

    // Add a method to find a photo by ID and user
    Optional<PhotoEntity> findById(UUID id);

    Set<PhotoEntity> findBySpaceIdAndIdIn(UUID space, List<UUID> ids);

    Page<PhotoEntity> findBySpaceId(UUID space, Pageable pageable);

    Optional<PhotoEntity> findByFileHash(String hash);

    Set<PhotoEntity> findByIdIn(List<UUID> photoId);

    // Used by the share/guest flow to confirm a photo actually belongs to the album a share
    // link grants access to, before serving it (guest sessions never have space-filtering
    // applied, so this containment check is the real authorization boundary for them).
    Optional<PhotoEntity> findByIdAndAlbums_IdAndIsGarbageFalse(UUID id, UUID albumId);
}