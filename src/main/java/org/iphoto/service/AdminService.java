package org.iphoto.service;

import org.iphoto.model.StorageMigrationSummaryDTO;
import org.iphoto.model.TaskAdminDTO;
import org.iphoto.model.TaskStatus;
import org.iphoto.model.TaskType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AdminService {

    // Replaces the old one-shot "activate face recognition" admin button: a plain boolean switch
    // pausing/resuming heavy task processing as a whole (see TaskWorkerManager#pollHeavyTasks).
    void activateHeavyTasks(boolean activate);

    boolean isHeavyTasksActivated();

    // Optional, admin-triggered backfill - see StorageMigrationService for why this isn't automatic.
    StorageMigrationSummaryDTO migrateFlatFilesToOrganizedStorage();

    void updateImageTaskToDo();

    // Admin > Tasks page (specification.md's Admin section) - either filter may be null (no
    // filtering on that field).
    Page<TaskAdminDTO> getTasks(TaskStatus status, TaskType taskType, Pageable pageable);

    // Resets one task back to PENDING regardless of its current status - see
    // AdminServiceImpl#resetToPending for why this is intentionally not restricted to
    // terminal (COMPLETED/FAILED/IGNORED/TRASHED) statuses.
    TaskAdminDTO restartTask(UUID taskId);

    // Bulk counterpart for the Tasks page's mass-selection "Restart Selected" action - unknown ids
    // are silently skipped (same as TaskRepository#findAllById), returns how many were actually
    // reset.
    int restartTasks(List<UUID> taskIds);

    // Admin > Tasks page "Reprocessing" panel. Each resets a whole stage so it runs again from
    // scratch:
    //  - restartFaceExtraction: every ISOLATE_FACES task back to PENDING (FaceDetectionService
    //    deletes+re-detects a photo's faces on each run, so this is a clean re-extraction).
    //  - restartDuplicationDetection: every CHECK_DUPLICATION task back to PENDING.
    // Both return how many task rows were reset.
    int restartFaceExtraction();

    int restartDuplicationDetection();

    // restartPersonRecognition: wipes all grouping - deletes every person (+ their embedding
    // buckets + merge suggestions), detaches every face and marks it NEW, and kicks the perpetual
    // FACE_RECOGNITION task to run now so it re-groups from nothing. The faces themselves (the
    // expensive detection/embedding work) are kept. Returns {"personsDeleted", "facesReset"}.
    Map<String, Integer> restartPersonRecognition();
}
