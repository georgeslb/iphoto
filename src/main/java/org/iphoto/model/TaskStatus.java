package org.iphoto.model;

public enum TaskStatus {
    PENDING,
    // Claimed by a poller (see TaskWorkerManager#pollPendingTasks/pollHeavyTasks) and submitted to
    // an executor, but not yet actually running on a worker thread - set BEFORE submit() so the
    // next poll tick's PENDING query never re-selects (and so re-submits) the same row while it's
    // still just sitting in the executor's internal queue. Transitions to PROCESSING the moment
    // the submitted runnable actually starts (see TaskWorkerManager#markProcessing).
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED,
    IGNORED, TRASHED
}
