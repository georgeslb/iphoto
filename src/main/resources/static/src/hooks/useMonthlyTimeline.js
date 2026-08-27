import { useState, useEffect, useCallback } from 'react';

// The Library "month/year index" — one {year, month, count} bucket per calendar month that has
// at least one photo, newest first. LibraryTimeline.jsx's Memories strip and browse-by-month
// list both read from this single fetch instead of each doing their own query (deliberately
// designed that way — see TODO.md's Memories/timeline entry).
//
// `active` gates the fetch so it only runs while actually on the Library view (App.jsx passes
// `selectedMenu === 'library'`) — no point fetching this on every other route.
export function useMonthlyTimeline(active) {
  const [months, setMonths] = useState([]);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch('/api/photos/timeline');
      if (!res.ok) throw new Error('failed to fetch timeline');
      setMonths(await res.json());
    } catch (err) {
      console.error('failed to fetch photo timeline', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (active) refresh();
  }, [active, refresh]);

  return { months, loading, refresh };
}
