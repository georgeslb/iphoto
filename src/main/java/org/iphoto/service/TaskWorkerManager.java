package org.iphoto.service;

import org.springframework.scheduling.annotation.Scheduled;

public interface TaskWorkerManager {

    // @Scheduled(fixedDelay=...) guarantees the next tick only starts once this method returns -
    // one poller invocation runs start-to-finish on a single thread, never overlapping itself.
    // Heavy tasks have their own, much less frequent poller - see pollHeavyTasks.
    void pollLightPendingTasks();

    @Scheduled(fixedDelay = 5000)
    void pollMiddleChargeTasks();

    // Heavy tasks (CHECK_DUPLICATION/ISOLATE_FACES/FACE_RECOGNITION) are the expensive,
    // resource-intensive half of the pipeline - gated behind an admin on/off switch (see
    // AdminService#isHeavyTasksActivated/ACTIVATE_HEAVY_TASKS) so a struggling machine can pause
    // this half entirely without touching light/control tasks.
    void pollHeavyTasks();

    // General safety net, across every task type: covers a task left QUEUED/PROCESSING with no
    // poller ever picking it back up (worker thread died, app restart, submit() failure, ...).
    void reclaimStuckProcessingTasks();

    void shutdown();
}
