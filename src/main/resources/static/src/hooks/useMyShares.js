import { useState, useEffect, useCallback } from 'react';
import { useLocale } from '../LocaleContext.jsx';

export function useMyShares() {
  const { messages } = useLocale();
  const [shares, setShares] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [message, setMessage] = useState(null);

  const fetchShares = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch('/api/share/my-shares');
      if (!res.ok) throw new Error(messages["failedToFetchShares"]);
      const data = await res.json();
      const sorted = Array.isArray(data) ? [...data].sort((a, b) => {
        if (!a.createdAt && !b.createdAt) return 0;
        if (!a.createdAt) return 1;
        if (!b.createdAt) return -1;
        return new Date(b.createdAt) - new Date(a.createdAt);
      }) : [];
      setShares(sorted);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [messages]);

  useEffect(() => { fetchShares(); }, [fetchShares]);

  const handleDeleteShare = useCallback(async (id) => {
    if (!window.confirm(messages["confirmDeleteShareLink"])) return;
    setError(null);
    setMessage(null);
    try {
      const res = await fetch(`/api/share/${id}`, { method: 'DELETE' });
      if (!res.ok) throw new Error(messages["failedToDeleteShareLink"]);
      setMessage(messages["shareLinkDeleted"]);
      await fetchShares();
    } catch (err) {
      setError(err.message);
    }
  }, [messages, fetchShares]);

  const handleRemovePassword = useCallback(async (id) => {
    setError(null);
    setMessage(null);
    try {
      const res = await fetch(`/api/share/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password: null }),
      });
      if (!res.ok) throw new Error(messages["failedToUpdateShareLink"]);
      setMessage(messages["sharePasswordRemoved"]);
      await fetchShares();
    } catch (err) {
      setError(err.message);
    }
  }, [messages, fetchShares]);

  const handleExtendShare = useCallback(async (id, days) => {
    setError(null);
    setMessage(null);
    try {
      const res = await fetch(`/api/share/${id}/extend`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ days: Number(days) }),
      });
      if (!res.ok) {
        let errorMsg = messages["failedToExtendSharePeriod"];
        try {
          const errData = await res.json();
          if (errData?.message) errorMsg = errData.message;
        } catch (_) {}
        throw new Error(errorMsg);
      }
      setMessage(messages["sharePeriodExtended"]);
      await fetchShares();
      return true;
    } catch (err) {
      setError(err.message);
      return false;
    }
  }, [messages, fetchShares]);

  return { shares, loading, error, message, handleDeleteShare, handleRemovePassword, handleExtendShare };
}
