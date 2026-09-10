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

  return {
    tasks, pageNumber, totalPages, totalElements,
    statusFilter, setStatusFilter, taskTypeFilter, setTaskTypeFilter,
    loading, error, message, restartingIds,
    goToPage, restartTask, restartTasks,
  };
}
