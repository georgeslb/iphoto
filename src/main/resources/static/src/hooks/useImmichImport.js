import { useState, useCallback, useEffect } from 'react';
import { useLocale } from '../LocaleContext.jsx';

const TERMINAL_DAY_STATUSES = ['COMPLETED', 'PARTIAL', 'FAILED'];

// Backs the "Import from Immich" page (components/ImmichImportManagement.jsx) - per-user, not
// admin-only: each user connects their own Immich server (see /api/immich/settings) and imports
// only their own days/albums. Follows the same plain-fetch + hook conventions as
// hooks/useMyShares.js, and the same per-row setInterval/clearInterval polling shape as
// components/UploadManager.jsx's pollTaskStatus - here polling one Immich-import-day row instead
// of one upload task.
export function useImmichImport() {
  const { messages } = useLocale();
  const [settings, setSettings] = useState(null);
  const [settingsLoading, setSettingsLoading] = useState(true);
  const [days, setDays] = useState([]);
  const [coverage, setCoverage] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [message, setMessage] = useState(null);
  const [albumImportSummary, setAlbumImportSummary] = useState(null);
  const [albumImporting, setAlbumImporting] = useState(false);

  const fetchSettings = useCallback(async () => {
    setSettingsLoading(true);
    try {
      const res = await fetch('/api/immich/settings');
      if (!res.ok) throw new Error(messages["failedToFetchImmichSettings"]);
      setSettings(await res.json());
    } catch (err) {
      setError(err.message);
    } finally {
      setSettingsLoading(false);
    }
  }, [messages]);

  useEffect(() => { fetchSettings(); }, [fetchSettings]);

  const saveSettings = useCallback(async (baseUrl, apiKey) => {
    setError(null);
    setMessage(null);
    try {
      const res = await fetch('/api/immich/settings', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ baseUrl, apiKey }),
      });
      if (!res.ok) throw new Error(messages["failedToSaveImmichSettings"]);
      setSettings(await res.json());
      setMessage(messages["immichSettingsSaved"]);
    } catch (err) {
      setError(err.message);
    }
  }, [messages]);

  const disconnect = useCallback(async () => {
    setError(null);
    setMessage(null);
    try {
      const res = await fetch('/api/immich/settings', { method: 'DELETE' });
      if (!res.ok) throw new Error(messages["failedToSaveImmichSettings"]);
      setSettings(await res.json());
      setDays([]);
      setCoverage(null);
    } catch (err) {
      setError(err.message);
    }
  }, [messages]);

  const applyOverview = useCallback((overview) => {
    setDays(overview.days || []);
    setCoverage(overview.coverage || null);
  }, []);

  const fetchOverview = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch('/api/immich/days');
      if (!res.ok) throw new Error(messages["failedToFetchImmichDays"]);
      applyOverview(await res.json());
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [messages, applyOverview]);

  useEffect(() => {
    if (settings && settings.connected) fetchOverview();
    else setLoading(false);
  }, [settings, fetchOverview]);

  const syncDays = useCallback(async () => {
    setError(null);
    setMessage(null);
    try {
      const res = await fetch('/api/immich/sync-days', { method: 'POST' });
      if (!res.ok) throw new Error(messages["failedToSyncImmichDays"]);
      applyOverview(await res.json());
      setMessage(messages["immichDaysSynced"]);
    } catch (err) {
      setError(err.message);
    }
  }, [messages, applyOverview]);

  const pollDay = useCallback((day) => {
    const interval = setInterval(async () => {
      try {
        const res = await fetch(`/api/immich/days/${day}`);
        if (!res.ok) throw new Error(messages["serverRespondedWithError"](res.status, res.statusText));
        const updated = await res.json();
        setDays(prev => prev.map(d => d.day === day ? updated : d));
        if (TERMINAL_DAY_STATUSES.includes(updated.status)) {
          clearInterval(interval);
        }
      } catch (err) {
        clearInterval(interval);
        console.error(messages["pollingFailed"](err.message));
      }
    }, 3000);
  }, [messages]);

  const startImport = useCallback(async (day) => {
    setError(null);
    setMessage(null);
    try {
      const res = await fetch(`/api/immich/days/${day}/import`, { method: 'POST' });
      if (!res.ok) throw new Error(messages["failedToStartImmichImport"]);
      const updated = await res.json();
      setDays(prev => prev.map(d => d.day === day ? updated : d));
      if (!TERMINAL_DAY_STATUSES.includes(updated.status)) {
        pollDay(day);
      }
    } catch (err) {
      setError(err.message);
    }
  }, [messages, pollDay]);

  const importAlbums = useCallback(async () => {
    setError(null);
    setMessage(null);
    setAlbumImporting(true);
    try {
      const res = await fetch('/api/immich/albums/import', { method: 'POST' });
      if (!res.ok) throw new Error(messages["failedToImportImmichAlbums"]);
      setAlbumImportSummary(await res.json());
    } catch (err) {
      setError(err.message);
    } finally {
      setAlbumImporting(false);
    }
  }, [messages]);

  return {
    settings, settingsLoading, saveSettings, disconnect,
    days, coverage, loading, error, message,
    albumImportSummary, albumImporting,
    syncDays, startImport, importAlbums,
  };
}
