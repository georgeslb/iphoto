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

const IMPORTABLE_STATUSES = ['NOT_IMPORTED', 'PARTIAL', 'FAILED'];

export default function ImmichImportManagement() {
  const { messages } = useLocale();
  const {
    settings, settingsLoading, saveSettings, disconnect,
    days, coverage, loading, error, message,
    albumImportSummary, albumImporting,
    syncDays, startImport, importAlbums,
  } = useImmichImport();

  const [baseUrl, setBaseUrl] = useState('');
  const [apiKey, setApiKey] = useState('');

  useEffect(() => {
    if (settings) setBaseUrl(settings.baseUrl || '');
  }, [settings]);

  const anyDayImported = days.some(d => d.status === 'COMPLETED' || d.status === 'PARTIAL');

  const handleSaveSettings = (e) => {
    e.preventDefault();
    saveSettings(baseUrl, apiKey);
    setApiKey('');
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
          <button type="button" onClick={syncDays}>{messages["immichSyncDaysButton"]}</button>
          {anyDayImported && (
            <button type="button" onClick={importAlbums} disabled={albumImporting} style={{ marginLeft: 8 }}>
              {albumImporting ? messages["immichImportingAlbumsButton"] : messages["immichImportAlbumsButton"]}
            </button>
          )}

          {coverage && (
            <div className={`immich-coverage-banner ${coverage.unaccountedForCount > 0 ? 'warning' : 'ok'}`}>
              {messages["immichCoverageSummary"](coverage.importedTotal, coverage.immichTotal)}
              {coverage.unaccountedForCount > 0 && (
                <> {messages["immichCoverageWarning"](coverage.unaccountedForCount)}</>
              )}
            </div>
          )}

          {albumImportSummary && (
            <div className="immich-coverage-banner ok">
              {messages["immichAlbumImportSummary"](
                albumImportSummary.albumsCreated,
                albumImportSummary.albumsUpdated,
                albumImportSummary.photosLinked,
                albumImportSummary.sharedLinksCreated
              )}
              {albumImportSummary.warnings && albumImportSummary.warnings.length > 0 && (
                <ul>
                  {albumImportSummary.warnings.map((w, i) => <li key={i}>{w}</li>)}
                </ul>
              )}
            </div>
          )}

          {loading ? (
            <p>{messages["immichLoading"]}</p>
          ) : days.length === 0 ? (
            <p>{messages["immichNoDaysYet"]}</p>
          ) : (
            <table className="users-table">
              <thead>
                <tr>
                  <th>{messages["immichDayColumn"]}</th>
                  <th>{messages["immichAssetCountColumn"]}</th>
                  <th>{messages["tableHeaderStatus"]}</th>
                  <th>{messages["tableHeaderActions"]}</th>
                </tr>
              </thead>
              <tbody>
                {days.map(d => (
                  <tr key={d.day}>
                    <td>{d.day}</td>
                    <td>{d.immichAssetCount}</td>
                    <td>
                      <span className={`status-text ${d.status}`}>{messages[STATUS_MESSAGE_KEYS[d.status]] || d.status}</span>
                      <div>{messages["immichDaySummary"](d.importedCount || 0, d.failedCount || 0, d.queuedCount || d.immichAssetCount || 0)}</div>
                    </td>
                    <td>
                      <button
                        type="button"
                        disabled={!IMPORTABLE_STATUSES.includes(d.status)}
                        onClick={() => startImport(d.day)}
                      >
                        {d.status === 'PARTIAL' || d.status === 'FAILED' ? messages["immichRetryButton"] : messages["immichImportDayButton"]}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </div>
  );
}
