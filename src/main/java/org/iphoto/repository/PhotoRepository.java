package org.iphoto.repository;

import org.iphoto.model.PhotoEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PhotoRepository extends JpaRepository<PhotoEntity, UUID> {
    
    Optional<PhotoEntity> findByFileHash(String fileHash);
    
    Page<PhotoEntity> findByIsGarbageFalse(Pageable pageable);
    
    Page<PhotoEntity> findByIsGarbageTrue(Pageable pageable);

    @Query("SELECT p.fileHash FROM PhotoEntity p WHERE p.isGarbage = false GROUP BY p.fileHash HAVING COUNT(p.fileHash) > 1")
    List<String> findDuplicateHashes();
    
    List<PhotoEntity> findByFileHashInAndIsGarbageFalse(List<String> hashes);
}
