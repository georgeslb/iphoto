import { useState, useCallback, useEffect } from 'react';

// Library page banner (PersonMergeSuggestionBanner.jsx) - two separate calls instead of one big
// upfront fetch of every suggestion. GET .../merge-suggestions/count is cheap (no face images at
// all, see FaceController#getPersonMergeSuggestionsCount) and is all the banner text needs before
// the popup is even opened; GET .../merge-suggestions?page=N&size=1 fetches exactly the one
// suggestion (with its two base64 face thumbnails) the popup is about to show. The popup only
// ever displays one pair at a time (PersonMergeSuggestionModal.jsx), so fetching every OTHER
// suggestion's face images up front - the old behavior, one flat unpaginated list - was pure
// waste: a real library with many suggestions turned a banner that only ever needs a number into
// a multi-megabyte, multi-second load.
export function usePersonMergeSuggestions() {
  const [count, setCount] = useState(0);
  const [countLoaded, setCountLoaded] = useState(false);
  const [current, setCurrent] = useState(null); // the one currently-fetched suggestion, or null
  const [activeIndex, setActiveIndex] = useState(0);
  const [loading, setLoading] = useState(false);

  const fetchCount = useCallback(async () => {
    try {
      const res = await fetch('/api/faces/persons/merge-suggestions/count');
      const data = await res.json();
      setCount(data.count || 0);
    } catch (err) {
      console.error('Failed to fetch person merge suggestion count', err);
      setCount(0);
    } finally {
      setCountLoaded(true);
    }
  }, []);

  useEffect(() => { fetchCount(); }, [fetchCount]);

  const fetchSuggestionAt = useCallback(async (index) => {
    setLoading(true);
    try {
      const res = await fetch(`/api/faces/persons/merge-suggestions?page=${index}&size=1`);
      const data = await res.json();
      const suggestion = (data.content && data.content[0]) || null;
      setCurrent(suggestion);
      setActiveIndex(index);
      return suggestion;
    } catch (err) {
      console.error('Failed to fetch person merge suggestion', err);
      setCurrent(null);
      return null;
    } finally {
      setLoading(false);
    }
  }, []);

  // Opens the popup at a given index (always 0, from the banner's Review button).
  const openAt = useCallback((index) => fetchSuggestionAt(index), [fetchSuggestionAt]);

  const goToNext = useCallback(() => {
    if (count === 0) return;
    fetchSuggestionAt((activeIndex + 1) % count);
  }, [count, activeIndex, fetchSuggestionAt]);

  // Called after a successful merge - the suggestion at activeIndex is gone server-side, so
  // re-fetching that SAME index naturally shows what used to be the following suggestion (or
  // nothing, if that was the last one), the same "shift down" behavior the old array-splice
  // approach had, just re-fetched from the server instead of spliced client-side.
  const refreshAfterMerge = useCallback(async () => {
    let newCount = count;
    try {
      const res = await fetch('/api/faces/persons/merge-suggestions/count');
      const data = await res.json();
      newCount = data.count || 0;
    } catch (err) {
      console.error('Failed to refresh person merge suggestion count', err);
    }
    setCount(newCount);
    if (newCount === 0) {
      setCurrent(null);
      return;
    }
    const nextIndex = activeIndex >= newCount ? newCount - 1 : activeIndex;
    await fetchSuggestionAt(nextIndex);
  }, [count, activeIndex, fetchSuggestionAt]);

  return { count, countLoaded, current, activeIndex, loading, openAt, goToNext, refreshAfterMerge };
}
