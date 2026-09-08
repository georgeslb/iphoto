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

    // Un-counts one or more PREVIOUSLY-recorded failures for an asset that's since turned out to
    // be genuinely, fully imported after all (see ImmichImportServiceImpl#reconcileOrphanedCompletedAsset -
    // a lingering FAILED task for an asset whose row+file both actually exist, most commonly left
    // behind by a losing concurrent/duplicate execution of the same UPLOAD_FROM_IMMICH task - see
    // TaskWorkerManagerImpl's queuedTasks dedup guard for the actual race this comes from).
    // Clamped at 0 (CASE WHEN, not a plain subtraction) so an unexpected extra call can never send
    // failedCount negative. Atomic for the same reason incrementFailed is - concurrent workers.
    @Modifying(clearAutomatically = true)
    @Query("update ImmichImportDayEntity d set d.failedCount = CASE WHEN d.failedCount - :count < 0 THEN 0 ELSE d.failedCount - :count END, d.updatedAt = CURRENT_TIMESTAMP where d.id = :id")
    void decrementFailed(@Param("id") UUID id, @Param("count") int count);
}
