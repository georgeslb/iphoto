package org.iphoto.service;

import org.iphoto.entities.PhotoEntity;
import org.iphoto.entities.TaskEntity;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface DuplicationService {

    void deleteDuplicatesForPhoto(PhotoEntity photo);

    void processMovingToGarbage(Set<PhotoEntity> photosMovedToGarbage);

    void restoreFromGarbage(List<PhotoEntity> photosRestored);

    void hashImage(TaskEntity task);

    // No longer self-locking - CHECK_DUPLICATION now runs on its own dedicated single-thread pool
    // (see TaskWorkerManager/IPhotoProperties.HeavyTasks#duplicationThreads), which already
    // guarantees only one call runs at a time.
    void checkDuplication(TaskEntity task);

    Set<PhotoEntity> getDuplicates(PhotoEntity photo);

    void unlinkPhotoFromGroup(UUID photoId, Set<UUID> photosId);
}
