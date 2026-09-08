package org.iphoto.service;

public interface TaskWorkerManager {

    // @Scheduled(fixedDelay=...) guarantees the next tick only starts once this method returns -
    // one poller invocation runs start-to-finish on a single thread, never overlapping itself.
    // Heavy tasks have their own, much less frequent poller - see pollHeavyTasks.
    void pollLightPendingTasks();

    void pollMiddleChargeTasks();

    // Heavy tasks (CHECK_DUPLICATION/ISOLATE_FACES/FACE_RECOGNITION) are the expensive,
    // resource-intensive half of the pipeline - gated behind an admin on/off switch (see
    // AdminService#isHeavyTasksActivated/ACTIVATE_HEAVY_TASKS) so a struggling machine can pause
    // this half entirely without touching light/control tasks.
    void pollFaceRecognitionTask();

    void pollDuplicationTask();

    // General safety net, across every task type: covers a task left QUEUED/PROCESSING with no
    // poller ever picking it back up (worker thread died, app restart, submit() failure, ...).
    void reclaimStuckProcessingTasks();

    // Periodic visibility into each pool's own backlog (queued = submitted but no free thread yet,
    // active = actually running right now) - the only place all four executors' state is reported
    // together, so a growing backlog on one specific pool (vs. the others) is visible directly in
    // the logs rather than only inferrable from how fast tasks move through pollLightPendingTasks/
    // etc.
    void logThreadPoolStatus();

    void shutdown();
}
