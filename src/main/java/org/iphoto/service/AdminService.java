package org.iphoto.service;

import org.iphoto.model.StorageMigrationSummaryDTO;

public interface AdminService {

    // Replaces the old one-shot "activate face recognition" admin button: a plain boolean switch
    // pausing/resuming heavy task processing as a whole (see TaskWorkerManager#pollHeavyTasks).
    void activateHeavyTasks(boolean activate);

    boolean isHeavyTasksActivated();

    // Optional, admin-triggered backfill - see StorageMigrationService for why this isn't automatic.
    StorageMigrationSummaryDTO migrateFlatFilesToOrganizedStorage();

    void updateImageTaskToDo();
}
