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
      setShares(await res.json());
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

  return { shares, loading, error, message, handleDeleteShare, handleRemovePassword };
}
