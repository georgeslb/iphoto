-- Backs TaskEntity.durationMs / the Admin > Tasks page "Duration" column: how long the last run
-- of each task took start-to-finish, in milliseconds (TaskWorkerManagerImpl#processTask). Nullable
-- - null until a task has run at least once, and pre-existing rows never get backfilled.
alter table tasks add column duration_ms bigint;
