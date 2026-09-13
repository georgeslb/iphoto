import { useState, useRef, useCallback, useEffect } from 'react';

// Lazily-loaded, flat (non-day-grouped) "recently added" list - the opposite of
// useDayIndexedPhotos.js's eager day-index fetch: nothing is fetched until the caller actually
// calls loadMore (RecentlyAddedSection.jsx does this on first expand, not on mount), and pages are
// appended on demand as the caller scrolls, rather than day-by-day. `baseUrl` is one of
// GET /api/photos/recent or GET /api/photos/space/{spaceId}/recent (both fixed at 20/page,
// sorted by upload time - see PhotoController).
export function useRecentPhotos(baseUrl) {
  const [photos, setPhotos] = useState([]);
  const [loading, setLoading] = useState(false);
  const [isLast, setIsLast] = useState(false);
  const [hasLoaded, setHasLoaded] = useState(false);

  // Refs mirror useDayIndexedPhotos.js's idiom: loadMore reads these instead of closing over
  // state, so its identity stays stable and two near-simultaneous scroll events can't both pass
  // the loading/isLast guard and double-fetch the same page.
  const loadingRef = useRef(false);
  const isLastRef = useRef(false);
  const nextPageRef = useRef(0);

  // Resets if baseUrl itself changes (e.g. navigating from one Space detail straight to another
  // without unmounting RecentlyAddedSection) - otherwise the accumulated list/pagination cursor
  // would silently keep belonging to the previous space.
  useEffect(() => {
    setPhotos([]);
    setLoading(false);
    setIsLast(false);
    setHasLoaded(false);
    loadingRef.current = false;
    isLastRef.current = false;
    nextPageRef.current = 0;
  }, [baseUrl]);

  const loadMore = useCallback(async () => {
    if (loadingRef.current || isLastRef.current) return;
    loadingRef.current = true;
    setLoading(true);
    try {
      const res = await fetch(`${baseUrl}?page=${nextPageRef.current}`);
      const data = await res.json();
      setPhotos(prev => [...prev, ...(data.content || [])]);
      isLastRef.current = data.last !== false;
      setIsLast(isLastRef.current);
      nextPageRef.current += 1;
    } catch (err) {
      console.error('Failed to load recent photos', err);
    } finally {
      loadingRef.current = false;
      setLoading(false);
      setHasLoaded(true);
    }
  }, [baseUrl]);

  return { photos, loading, isLast, hasLoaded, loadMore };
}
