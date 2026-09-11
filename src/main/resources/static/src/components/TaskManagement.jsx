import React, { useState, useEffect } from 'react';
import './UserManagement.css'; // Reused for its generic container/table styling
import { useLocale } from '../LocaleContext.jsx';
import { useTaskManagement } from '../hooks/useTaskManagement';

// Mirrors org.iphoto.model.TaskStatus - kept in sync manually since the frontend has no build-time
// link to the backend enum (same convention as ImmichImportManagement.jsx's own STATUS_FILTER_VALUES).
const TASK_STATUS_VALUES = ['ALL', 'PENDING', 'PARKED', 'QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED', 'IGNORED', 'TRASHED'];

// Mirrors org.iphoto.model.TaskType.
const TASK_TYPE_VALUES = [
  'ALL', 'PERMANENT_DELETE', 'PROCESS_UPLOAD', 'HASH_IMAGE', 'ACTIVATE_HEAVY_TASKS',
  'CHECK_DUPLICATION', 'ISOLATE_FACES', 'FACE_RECOGNITION', 'SYNC_FROM_IMMICH',
  'UPLOAD_FROM_IMMICH', 'IMPORT_IMMICH_ALBUM',
];

const STATUS_MESSAGE_KEYS = {
  PENDING: 'taskStatusPending',
  PARKED: 'taskStatusParked',
  QUEUED: 'taskStatusQueued',
  PROCESSING: 'taskStatusProcessing',
  COMPLETED: 'taskStatusCompleted',
  FAILED: 'taskStatusFailed',
  IGNORED: 'taskStatusIgnored',
  TRASHED: 'taskStatusTrashed',
};

const ERROR_PREVIEW_LENGTH = 80;

function formatTimestamp(value) {
  if (!value) return '-';
  return new Date(value).toLocaleString();
}

export default function TaskManagement() {
  const { messages } = useLocale();
  const {
    tasks, pageNumber, totalPages, totalElements,
    statusFilter, setStatusFilter, taskTypeFilter, setTaskTypeFilter,
    loading, error, message, restartingIds, reprocessing,
    goToPage, restartTask, restartTasks, parkTask, unparkTask,
    reprocessFaces, reprocessDuplicates, reprocessPersons,
  } = useTaskManagement();

  const [selectedIds, setSelectedIds] = useState(() => new Set());

  // Selection is scoped to the currently-loaded page - clear it whenever the page or a filter
  // changes so a checked row from a previous page/filter can't linger into a bulk action it was
  // never actually shown alongside.
  useEffect(() => {
    setSelectedIds(new Set());
  }, [pageNumber, statusFilter, taskTypeFilter]);

  const restartableIds = tasks.filter(t => t.status !== 'PENDING').map(t => t.id);
  const allVisibleSelected = restartableIds.length > 0 && restartableIds.every(id => selectedIds.has(id));

  const toggleSelection = (id) => {
    setSelectedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const toggleSelectAllVisible = () => {
    setSelectedIds(prev => {
      if (allVisibleSelected) return new Set();
      return new Set(restartableIds);
    });
  };

  const handleRestartSelected = async () => {
    await restartTasks(Array.from(selectedIds));
    setSelectedIds(new Set());
  };

  return (
    <div className="user-management-container">
      <h2>{messages["taskManagementTitle"]}</h2>
      <p>{messages["taskManagementDescription"]}</p>

      {error && <p className="error-message">{error}</p>}
      {message && <p className="success-message">{message}</p>}

      <div className="task-reprocess-panel">
        <h3>{messages["reprocessTitle"]}</h3>
        <p className="task-reprocess-hint">{messages["reprocessHint"]}</p>
        <div className="task-reprocess-buttons">
          <button type="button" disabled={reprocessing !== null} onClick={reprocessFaces}>
            {reprocessing === 'faces' ? messages["reprocessRunning"] : messages["reprocessFacesButton"]}
          </button>
          <button type="button" disabled={reprocessing !== null} onClick={reprocessDuplicates}>
            {reprocessing === 'duplicates' ? messages["reprocessRunning"] : messages["reprocessDuplicatesButton"]}
          </button>
          <button
            type="button"
            className="reprocess-danger"
            disabled={reprocessing !== null}
            onClick={reprocessPersons}
          >
            {reprocessing === 'persons' ? messages["reprocessRunning"] : messages["reprocessPersonsButton"]}
          </button>
        </div>
      </div>

      <div className="task-management-toolbar">
        <label>
          {messages["taskStatusFilterLabel"]}
          <select value={statusFilter} onChange={e => setStatusFilter(e.target.value)}>
            {TASK_STATUS_VALUES.map(status => (
              <option key={status} value={status}>
                {status === 'ALL' ? messages["taskFilterAll"] : messages[STATUS_MESSAGE_KEYS[status]]}
              </option>
            ))}
          </select>
        </label>
        <label>
          {messages["taskTypeFilterLabel"]}
          <select value={taskTypeFilter} onChange={e => setTaskTypeFilter(e.target.value)}>
            {TASK_TYPE_VALUES.map(type => (
              <option key={type} value={type}>{type === 'ALL' ? messages["taskFilterAll"] : type}</option>
            ))}
          </select>
        </label>
        <button
          type="button"
          onClick={handleRestartSelected}
          disabled={selectedIds.size === 0}
        >
          {messages["restartSelectedButton"](selectedIds.size)}
        </button>
      </div>

      {loading ? (
        <p>{messages["immichLoading"]}</p>
      ) : tasks.length === 0 ? (
        <p>{messages["noTasksMessage"]}</p>
      ) : (
        <>
          <table className="users-table task-table">
            <thead>
              <tr>
                <th>
                  <input
                    type="checkbox"
                    aria-label={messages["taskSelectAllLabel"]}
                    checked={allVisibleSelected}
                    disabled={restartableIds.length === 0}
                    onChange={toggleSelectAllVisible}
                  />
                </th>
                <th>{messages["tableHeaderTaskId"]}</th>
                <th>{messages["tableHeaderTaskType"]}</th>
                <th>{messages["tableHeaderStatus"]}</th>
                <th>{messages["tableHeaderPhotoId"]}</th>
                <th>{messages["tableHeaderError"]}</th>
                <th>{messages["tableHeaderCreatedAt"]}</th>
                <th>{messages["tableHeaderUpdatedAt"]}</th>
                <th>{messages["tableHeaderDuration"]}</th>
                <th>{messages["tableHeaderActions"]}</th>
              </tr>
            </thead>
            <tbody>
              {tasks.map(task => {
                const restartable = task.status !== 'PENDING';
                const restarting = restartingIds.has(task.id);
                return (
                  <tr key={task.id}>
                    <td>
                      <input
                        type="checkbox"
                        checked={selectedIds.has(task.id)}
                        disabled={!restartable}
                        onChange={() => toggleSelection(task.id)}
                      />
                    </td>
                    <td className="task-id-cell" title={task.id}>{task.id}</td>
                    <td>{task.taskType}</td>
                    <td><span className={`status-text ${task.status}`}>{messages[STATUS_MESSAGE_KEYS[task.status]] || task.status}</span></td>
                    <td className="task-id-cell" title={task.photoId || ''}>{task.photoId || '-'}</td>
                    <td title={task.errorMessage || ''}>
                      {task.errorMessage
                        ? (task.errorMessage.length > ERROR_PREVIEW_LENGTH
                            ? `${task.errorMessage.slice(0, ERROR_PREVIEW_LENGTH)}…`
                            : task.errorMessage)
                        : '-'}
                    </td>
                    <td>{formatTimestamp(task.createdAt)}</td>
                    <td>{formatTimestamp(task.updatedAt)}</td>
                    <td>{task.durationMs != null ? `${task.durationMs.toLocaleString()} ms` : '-'}</td>
                    <td>
                      <button
                        type="button"
                        disabled={!restartable || restarting}
                        onClick={() => restartTask(task.id)}
                      >
                        {restarting ? messages["restartingButton"] : messages["restartButton"]}
                      </button>
                      {task.status === 'PARKED' ? (
                        <button
                          type="button"
                          disabled={restarting}
                          onClick={() => unparkTask(task.id)}
                        >
                          {restarting ? messages["unparkingButton"] : messages["unparkButton"]}
                        </button>
                      ) : (
                        <button
                          type="button"
                          disabled={restarting}
                          onClick={() => parkTask(task.id)}
                        >
                          {restarting ? messages["parkingButton"] : messages["parkButton"]}
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>

          <div className="task-management-pagination">
            <button type="button" onClick={() => goToPage(pageNumber - 1)} disabled={pageNumber <= 0}>
              {messages["previousPageButton"]}
            </button>
            <span>{messages["paginationPageInfo"](pageNumber + 1, Math.max(totalPages, 1), totalElements)}</span>
            <button type="button" onClick={() => goToPage(pageNumber + 1)} disabled={pageNumber + 1 >= totalPages}>
              {messages["nextPageButton"]}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
