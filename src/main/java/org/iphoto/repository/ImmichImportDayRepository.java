package org.iphoto.repository;

import org.iphoto.entities.ImmichImportDayEntity;
import org.iphoto.entities.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ImmichImportDayRepository extends JpaRepository<ImmichImportDayEntity, UUID> {

    Optional<ImmichImportDayEntity> findByDayAndUser(LocalDate day, UserEntity user);

    List<ImmichImportDayEntity> findAllByUserOrderByDayDesc(UserEntity user);

    // Atomic increments, not read-modify-write: many UPLOAD_FROM_IMMICH tasks for the same day
    // finish concurrently on different virtual threads (see TaskWorkerManager), so a naive
    // load-mutate-save here would lose updates under contention.
    @Modifying(clearAutomatically = true)
    @Query("update ImmichImportDayEntity d set d.importedCount = d.importedCount + 1, d.updatedAt = CURRENT_TIMESTAMP where d.id = :id")
    void incrementImported(@Param("id") UUID id);

    @Modifying(clearAutomatically = true)
    @Query("update ImmichImportDayEntity d set d.skippedCount = d.skippedCount + 1, d.updatedAt = CURRENT_TIMESTAMP where d.id = :id")
    void incrementSkipped(@Param("id") UUID id);

    @Modifying(clearAutomatically = true)
    @Query("update ImmichImportDayEntity d set d.failedCount = d.failedCount + 1, d.updatedAt = CURRENT_TIMESTAMP where d.id = :id")
    void incrementFailed(@Param("id") UUID id);
}
