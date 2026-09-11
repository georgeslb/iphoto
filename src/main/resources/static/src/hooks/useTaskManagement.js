import { useState, useCallback, useEffect } from 'react';
import { useLocale } from '../LocaleContext.jsx';

const PAGE_SIZE = 50;

// Backs the Admin > Tasks page (components/TaskManagement.jsx) - admin-only management of the
// app's background tasks (TaskEntity/TaskWorkerManager). Follows the same plain-fetch + hook
// conventions as useImmichImport.js: page-based (not infinite-scroll) pagination since this is a
// data-management table, not a photo-browsing surface, backed by AdminController's real
// PageResponse<TaskAdminDTO>. Refetches from page 0 whenever a filter changes (see the effect
// below) - a filter change on an already-scrolled-in page would otherwise show a page number that
// no longer matches what's actually being filtered.
export function useTaskManagement() {
  const { messages } = useLocale();
  const [tasks, setTasks] = useState([]);
  const [pageNumber, setPageNumber] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [taskTypeFilter, setTaskTypeFilter] = useState('ALL');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [message, setMessage] = useState(null);
  const [restartingIds, setRestartingIds] = useState(() => new Set());
  // Which "Reprocessing" panel action is currently in flight ('faces' | 'persons' | 'duplicates'),
  // or null - drives the panel's disabled/busy state so two can't be fired at once.
  const [reprocessing, setReprocessing] = useState(null);

  const fetchTasks = useCallback(async (targetPage) => {
    setLoading(true);
    setError(null);
    try {
      const params = new URLSearchParams({ page: String(targetPage), size: String(PAGE_SIZE) });
      if (statusFilter !== 'ALL') params.set('status', statusFilter);
      if (taskTypeFilter !== 'ALL') params.set('taskType', taskTypeFilter);
      const res = await fetch(`/api/admin/tasks?${params.toString()}`);
      if (!res.ok) throw new Error(messages["failedToFetchTasks"]);
      const data = await res.json();
      setTasks(data.content || []);
      setPageNumber(data.pageNumber || 0);
      setTotalPages(data.totalPages || 0);
      setTotalElements(data.totalElements || 0);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [messages, statusFilter, taskTypeFilter]);

  // Resets to page 0 whenever the filters (or fetchTasks' own identity) change - a stale page
  // number from before a filter change would otherwise request a page that may not exist under
  // the new filter.
  useEffect(() => { fetchTasks(0); }, [fetchTasks]);

  const goToPage = useCallback((targetPage) => {
    fetchTasks(targetPage);
  }, [fetchTasks]);

  const restartTask = useCallback(async (taskId) => {
    setError(null);
    setMessage(null);
    setRestartingIds(prev => new Set(prev).add(taskId));
    try {
      const res = await fetch(`/api/admin/tasks/${taskId}/restart`, { method: 'POST' });
      if (!res.ok) throw new Error(messages["failedToRestartTask"]);
      const updated = await res.json();
      setTasks(prev => prev.map(t => t.id === taskId ? updated : t));
      setMessage(messages["taskRestarted"]);
    } catch (err) {
      setError(err.message);
    } finally {
      setRestartingIds(prev => {
        const next = new Set(prev);
        next.delete(taskId);
        return next;
      });
    }
  }, [messages]);

  const restartTasks = useCallback(async (taskIds) => {
    setError(null);
    setMessage(null);
    try {
      const res = await fetch('/api/admin/tasks/restart', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ taskIds }),
      });
      if (!res.ok) throw new Error(messages["failedToRestartTasks"]);
      const data = await res.json();
      setMessage(messages["tasksRestarted"](data.restarted));
      await fetchTasks(pageNumber);
    } catch (err) {
      setError(err.message);
    }
  }, [messages, fetchTasks, pageNumber]);

  // "Reprocessing" panel - re-run a whole pipeline stage (AdminController's /reprocess/* routes).
  // confirmMessage is shown via window.confirm first when set (the destructive persons one);
  // successMessage takes the parsed response body. Refetches the task list from page 0 after,
  // since every one of these leaves a batch of tasks freshly PENDING.
  const runReprocess = useCallback(async (kind, confirmMessage, successMessage) => {
    if (confirmMessage && !window.confirm(confirmMessage)) return;
    setError(null);
    setMessage(null);
    setReprocessing(kind);
    try {
      const res = await fetch(`/api/admin/reprocess/${kind}`, { method: 'POST' });
      if (!res.ok) throw new Error(messages["failedToReprocess"]);
      const data = await res.json();
      setMessage(successMessage(data));
      await fetchTasks(0);
    } catch (err) {
      setError(err.message);
    } finally {
      setReprocessing(null);
    }
  }, [messages, fetchTasks]);

  const reprocessFaces = useCallback(
    () => runReprocess('faces', null, data => messages["reprocessFacesDone"](data.tasksReset)),
    [runReprocess, messages]);

  const reprocessDuplicates = useCallback(
    () => runReprocess('duplicates', null, data => messages["reprocessDuplicatesDone"](data.tasksReset)),
    [runReprocess, messages]);

  const reprocessPersons = useCallback(
    () => runReprocess('persons', messages["reprocessPersonsConfirm"],
      data => messages["reprocessPersonsDone"](data.personsDeleted, data.facesReset)),
    [runReprocess, messages]);

  return {
    tasks, pageNumber, totalPages, totalElements,
    statusFilter, setStatusFilter, taskTypeFilter, setTaskTypeFilter,
    loading, error, message, restartingIds, reprocessing,
    goToPage, restartTask, restartTasks,
    reprocessFaces, reprocessDuplicates, reprocessPersons,
  };
}
