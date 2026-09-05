import React, { useState, useEffect } from 'react';
import './UserManagement.css'; // Reused for its generic container/table styling
import { useLocale } from '../LocaleContext.jsx';
import { useImmichImport } from '../hooks/useImmichImport';

const STATUS_MESSAGE_KEYS = {
  NOT_IMPORTED: 'immichDayStatusNotImported',
  IN_PROGRESS: 'immichDayStatusInProgress',
  COMPLETED: 'immichDayStatusCompleted',
  PARTIAL: 'immichDayStatusPartial',
  FAILED: 'immichDayStatusFailed',
};

// IN_PROGRESS is included alongside the rest: a day can get permanently stuck there if the app
// restarts mid-task (TaskWorkerManager only ever resumes PENDING tasks, never orphaned PROCESSING
// ones - see ImmichImportService.isReclaimable for the actual backend-side fix), with no other way
// to unstick it. Retriggering a *genuinely* still-running day is a harmless no-op server-side
// (nothing reclaimable yet), so there's no real downside to allowing it here too. COMPLETED is
// included so a user can re-check a day for assets added on Immich after the last sync -
// triggerDayImport is idempotent/re-runnable by design (see its own comment): it only ever queues
// what isn't already fully imported.
const IMPORTABLE_STATUSES = ['NOT_IMPORTED', 'PARTIAL', 'FAILED', 'COMPLETED', 'IN_PROGRESS'];

// Mirrors ImmichImportService.MAX_BULK_IMPORT_DAYS - kept in sync manually since the frontend has
// no build-time link to the backend constant; the backend still enforces its own copy of this cap
// regardless, this just keeps the UI from ever forming a request that would be rejected.
const MAX_BULK_IMPORT_DAYS = 30;

// Status filter dropdown options, in roughly the order a day moves through them. 'ALL' is the
// default (no filtering) - every real value after it is one of ImmichImportDayStatus. Labelled
// "Pending" for NOT_IMPORTED in the UI (see immichDayStatusNotImported's i18n text) since that's
// clearer to a user than "not imported" for a day that simply hasn't been queued yet.
const STATUS_FILTER_VALUES = ['ALL', 'NOT_IMPORTED', 'IN_PROGRESS', 'PARTIAL', 'FAILED', 'COMPLETED'];

// TaskStatus values an IMPORT_IMMICH_ALBUM task can be in (see useImmichImport's
// TERMINAL_ALBUM_TASK_STATUSES) - PENDING/QUEUED/PROCESSING are grouped under one "in progress"
// label since a user has no reason to distinguish "not yet picked up" from "actively running".
const ALBUM_TASK_STATUS_MESSAGE_KEYS = {
  PENDING: 'immichAlbumTaskStatusInProgress',
  QUEUED: 'immichAlbumTaskStatusInProgress',
  PROCESSING: 'immichAlbumTaskStatusInProgress',
  COMPLETED: 'immichAlbumTaskStatusCompleted',
  FAILED: 'immichAlbumTaskStatusFailed',
};

export default function ImmichImportManagement() {
  const { messages } = useLocale();
  const {
    settings, settingsLoading, saveSettings, disconnect,
    days, coverage, loading, error, message,
    albums, albumImportResult, albumImporting,
    syncDays, startImport, startBulkImport, importAlbums,
  } = useImmichImport();

  const [baseUrl, setBaseUrl] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [selectedDays, setSelectedDays] = useState(() => new Set());
  const [activeTab, setActiveTab] = useState('days');

  useEffect(() => {
    if (settings) setBaseUrl(settings.baseUrl || '');
  }, [settings]);

  const anyDayImported = days.some(d => d.status === 'COMPLETED' || d.status === 'PARTIAL');

  const handleSaveSettings = (e) => {
    e.preventDefault();
    saveSettings(baseUrl, apiKey);
    setApiKey('');
  };

  const visibleDays = statusFilter === 'ALL' ? days : days.filter(d => d.status === statusFilter);
  const importableVisibleDays = visibleDays.filter(d => IMPORTABLE_STATUSES.includes(d.status)).map(d => d.day);
  const allVisibleSelected = importableVisibleDays.length > 0 && importableVisibleDays.every(day => selectedDays.has(day));

  const toggleDaySelection = (day) => {
    setSelectedDays(prev => {
      const next = new Set(prev);
      if (next.has(day)) {
        next.delete(day);
      } else if (next.size < MAX_BULK_IMPORT_DAYS) {
        next.add(day);
      }
      return next;
    });
  };

  const toggleSelectAllVisible = () => {
    setSelectedDays(prev => {
      const next = new Set(prev);
      if (allVisibleSelected) {
        importableVisibleDays.forEach(day => next.delete(day));
      } else {
        for (const day of importableVisibleDays) {
          if (next.size >= MAX_BULK_IMPORT_DAYS) break;
          next.add(day);
        }
      }
      return next;
    });
  };

  const handleImportSelected = () => {
    startBulkImport(Array.from(selectedDays));
    setSelectedDays(new Set());
  };

  return (
    <div className="user-management-container">
      <h2>{messages["immichImportManagementTitle"]}</h2>
      <p>{messages["immichImportManagementDescription"]}</p>

      {error && <p className="error-message">{error}</p>}
      {message && <p className="success-message">{message}</p>}

      <section style={{ marginBottom: 24 }}>
        <h3>{messages["immichSettingsTitle"]}</h3>
        <p>{messages["immichSettingsDescription"]}</p>
        {!settingsLoading && (
          <>
            <p>
              {settings && settings.connected ? messages["immichConnectedStatus"] : messages["immichNotConnectedStatus"]}
            </p>
            <form onSubmit={handleSaveSettings} style={{ display: 'flex', flexDirection: 'column', gap: 8, maxWidth: 480 }}>
              <label>
                {messages["immichBaseUrlLabel"]}
                <input
                  type="text"
                  value={baseUrl}
                  onChange={e => setBaseUrl(e.target.value)}
                  placeholder="https://immich.example.com"
                />
              </label>
              <label>
                {messages["immichApiKeyLabel"]}
                <input
                  type="password"
                  value={apiKey}
                  onChange={e => setApiKey(e.target.value)}
                  placeholder={settings && settings.connected ? messages["immichApiKeyPlaceholderConnected"] : ''}
                />
              </label>
              <div>
                <button type="submit">{messages["immichSaveSettingsButton"]}</button>
                {settings && settings.connected && (
                  <button type="button" onClick={disconnect} style={{ marginLeft: 8 }}>
                    {messages["immichDisconnectButton"]}
                  </button>
                )}
              </div>
            </form>
          </>
        )}
      </section>

      {settings && settings.connected && (
        <>
          <div className="immich-tabs">
            <button
              type="button"
              className={`immich-tab-button${activeTab === 'days' ? ' active' : ''}`}
              onClick={() => setActiveTab('days')}
            >
              {messages["immichDaysTabLabel"]}
            </button>
            <button
              type="button"
              className={`immich-tab-button${activeTab === 'albums' ? ' active' : ''}`}
              onClick={() => setActiveTab('albums')}
            >
              {messages["immichAlbumsTabLabel"]}
            </button>
          </div>

          {activeTab === 'days' && (
            <>
              <button type="button" onClick={syncDays}>{messages["immichSyncDaysButton"]}</button>
              <label style={{ marginLeft: 8 }}>
                {messages["immichStatusFilterLabel"]}
                <select value={statusFilter} onChange={e => setStatusFilter(e.target.value)} style={{ marginLeft: 4 }}>
                  {STATUS_FILTER_VALUES.map(status => (
                    <option key={status} value={status}>
                      {status === 'ALL' ? messages["immichStatusFilterAll"] : messages[STATUS_MESSAGE_KEYS[status]]}
                    </option>
                  ))}
                </select>
              </label>
              <button
                type="button"
                onClick={handleImportSelected}
                disabled={selectedDays.size === 0}
                style={{ marginLeft: 8 }}
              >
                {messages["immichImportSelectedButton"](selectedDays.size, MAX_BULK_IMPORT_DAYS)}
              </button>

              {coverage && (
                <div className={`immich-coverage-banner ${coverage.unaccountedForCount > 0 ? 'warning' : 'ok'}`}>
                  {messages["immichCoverageSummary"](coverage.importedTotal, coverage.immichTotal)}
                  {coverage.unaccountedForCount > 0 && (
                    <> {messages["immichCoverageWarning"](coverage.unaccountedForCount)}</>
                  )}
                </div>
              )}

              {loading ? (
                <p>{messages["immichLoading"]}</p>
              ) : days.length === 0 ? (
                <p>{messages["immichNoDaysYet"]}</p>
              ) : visibleDays.length === 0 ? (
                <p>{messages["immichNoDaysMatchFilterMessage"]}</p>
              ) : (
                <table className="users-table">
                  <thead>
                    <tr>
                      <th>
                        <input
                          type="checkbox"
                          aria-label={messages["immichSelectAllLabel"]}
                          checked={allVisibleSelected}
                          disabled={importableVisibleDays.length === 0}
                          onChange={toggleSelectAllVisible}
                        />
                      </th>
                      <th>{messages["immichDayColumn"]}</th>
                      <th>{messages["immichAssetCountColumn"]}</th>
                      <th>{messages["tableHeaderStatus"]}</th>
                      <th>{messages["tableHeaderActions"]}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {visibleDays.map(d => {
                      const importable = IMPORTABLE_STATUSES.includes(d.status);
                      const selected = selectedDays.has(d.day);
                      return (
                        <tr key={d.day}>
                          <td>
                            <input
                              type="checkbox"
                              checked={selected}
                              disabled={!importable || (!selected && selectedDays.size >= MAX_BULK_IMPORT_DAYS)}
                              onChange={() => toggleDaySelection(d.day)}
                            />
                          </td>
                          <td>{d.day}</td>
                          <td>{d.immichAssetCount}</td>
                          <td>
                            <span className={`status-text ${d.status}`}>{messages[STATUS_MESSAGE_KEYS[d.status]] || d.status}</span>
                            <div>{messages["immichDaySummary"](d.importedCount || 0, d.failedCount || 0, d.queuedCount || d.immichAssetCount || 0)}</div>
                          </td>
                          <td>
                            <button
                              type="button"
                              disabled={!importable}
                              onClick={() => startImport(d.day)}
                            >
                              {d.status === 'PARTIAL' || d.status === 'FAILED' || d.status === 'IN_PROGRESS' ? messages["immichRetryButton"]
                                  : d.status === 'COMPLETED' ? messages["immichReimportButton"]
                                  : messages["immichImportDayButton"]}
                            </button>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              )}
            </>
          )}

          {activeTab === 'albums' && (
            <>
              {anyDayImported ? (
                <button type="button" onClick={importAlbums} disabled={albumImporting}>
                  {albumImporting ? messages["immichImportingAlbumsButton"] : messages["immichImportAlbumsButton"]}
                </button>
              ) : (
                <p>{messages["immichAlbumsNeedDaysFirst"]}</p>
              )}

              {albumImportResult && (
                <div className="immich-coverage-banner ok">
                  {messages["immichAlbumImportTriggerResult"](
                    albumImportResult.albumsQueued,
                    albumImportResult.albumsAlreadyInFlight,
                    albumImportResult.sharedLinksCreated
                  )}
                  {albumImportResult.warnings && albumImportResult.warnings.length > 0 && (
                    <ul>
                      {albumImportResult.warnings.map((w, i) => <li key={i}>{w}</li>)}
                    </ul>
                  )}
                </div>
              )}

              {albums.length > 0 ? (
                <table className="users-table">
                  <thead>
                    <tr>
                      <th>{messages["immichAlbumNameColumn"]}</th>
                      <th>{messages["immichAlbumPhotosLinkedColumn"]}</th>
                      <th>{messages["tableHeaderStatus"]}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {albums.map(a => (
                      <tr key={a.albumId}>
                        <td>{a.albumName}</td>
                        <td>{a.photoCount != null ? a.photoCount : '-'}</td>
                        <td>
                          <span className={`status-text ${a.status}`}>
                            {messages[ALBUM_TASK_STATUS_MESSAGE_KEYS[a.status]] || a.status}
                          </span>
                          {a.status === 'FAILED' && a.errorMessage && <div>{a.errorMessage}</div>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : anyDayImported && (
                <p>{messages["immichNoAlbumsYet"]}</p>
              )}
            </>
          )}
        </>
      )}
    </div>
  );
}
