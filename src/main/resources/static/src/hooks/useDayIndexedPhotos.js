import { useState, useEffect, useCallback, useRef } from 'react';

const PAGE_SIZE = 100;

// Every photo-listing view (Library/Album/Space/Garbage/Person) resolves to a target shape here -
// one technology for all of them. Mirrors usePhotoData.js's old type/albumId/spaceId/personId
// path-matching, now retired since day-index browsing replaced it everywhere.
export function resolveDayIndexTarget(effectivePath) {
  const albumMatch = effectivePath.match(/^\/albums\/(.+)$/);
  const spaceMatch = effectivePath.match(/^\/spaces\/(.+)$/);
  const personMatch = effectivePath.match(/^\/persons\/(.+)$/);
  if (albumMatch) return { type: 'album', albumId: albumMatch[1], spaceId: null, personId: null };
  if (spaceMatch) return { type: 'space', albumId: null, spaceId: spaceMatch[1], personId: null };
  if (personMatch) return { type: 'person', albumId: null, spaceId: null, personId: personMatch[1] };
  if (effectivePath === '/garbage') return { type: 'garbage', albumId: null, spaceId: null, personId: null };
  return { type: 'library', albumId: null, spaceId: null, personId: null };
}

function daysUrl({ type, albumId, spaceId, personId }) {
  if (type === 'album') return `/api/albums/${albumId}/days`;
  if (type === 'space') return `/api/photos/space/${spaceId}/days`;
  if (type === 'person') return `/api/faces/person/${personId}/days`;
  if (type === 'garbage') return '/api/photos/garbage/days';
  return '/api/photos/days';
}

function dayPhotosUrl({ type, albumId, spaceId, personId }, day, page) {
  const base = type === 'album'
    ? `/api/albums/${albumId}/photos/day/${day}`
    : type === 'space'
      ? `/api/photos/space/${spaceId}/day/${day}`
      : type === 'person'
        ? `/api/faces/person/${personId}/photos/day/${day}`
        : type === 'garbage'
          ? `/api/photos/garbage/day/${day}`
          : `/api/photos/day/${day}`;
  return `${base}?page=${page}&size=${PAGE_SIZE}`;
}

// Album's per-day endpoint returns a raw Spring Data Page (Spring Boot 3.3+'s
// {content, page:{size,number,totalElements,totalPages}} envelope, no top-level `last` -
// matching its existing /api/albums/{id}/photos sibling). Library/Space's endpoints return this
// app's own PageResponse ({content, pageNumber, pageSize, totalElements, totalPages, last}).
// Normalize both down to { content, isLast }.
function normalizePage(data) {
  if (Array.isArray(data)) {
    return { content: data, isLast: true };
  }
  if (data.page) {
    return { content: data.content || [], isLast: data.page.number >= data.page.totalPages - 1 };
  }
  return { content: data.content || [], isLast: data.last !== false };
}

// Day-index browsing for every photo-listing view (Library/Album/Space/Garbage/Person): fetch
// every day that has a photo up front (cheap - just a count + cover per day), then lazily fetch
// each day's actual photos (paginated, 100/page) only once that day's section scrolls into view.
// Replaces the old flat, page-number-paginated usePhotoData.js hook entirely (deleted).
export function useDayIndexedPhotos(effectivePath) {
  const [days, setDays] = useState([]);
  const [loadingDays, setLoadingDays] = useState(true);
  const [photosByDay, setPhotosByDay] = useState({});
  const [dayPageState, setDayPageState] = useState({});

  // Kept current every render (cheap, pure regex match) so loadDayPage/refreshDays can read the
  // latest target without needing effectivePath in their own dependency arrays.
  const targetRef = useRef(resolveDayIndexTarget(effectivePath));
  targetRef.current = resolveDayIndexTarget(effectivePath);

  // Mirrors usePhotoData.js's photosRef idiom: loadDayPage reads this instead of closing over
  // dayPageState, so its identity stays stable across renders.
  const dayPageStateRef = useRef(dayPageState);
  useEffect(() => { dayPageStateRef.current = dayPageState; }, [dayPageState]);

  const refreshDays = useCallback(async () => {
    setLoadingDays(true);
    try {
      const res = await fetch(daysUrl(targetRef.current));
      const data = await res.json();
      setDays(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Failed to fetch day index', err);
      setDays([]);
    } finally {
      setLoadingDays(false);
    }
  }, []);

  useEffect(() => {
    setPhotosByDay({});
    setDayPageState({});
    refreshDays();
    // refreshDays is stable (reads targetRef instead of closing over effectivePath); this effect
    // should only re-run because effectivePath changed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [effectivePath]);

  const loadDayPage = useCallback(async (day) => {
    const current = dayPageStateRef.current[day];
    if (current?.loading || current?.isLast) return;
    const page = current?.nextPage ?? 0;

    setDayPageState(prev => ({ ...prev, [day]: { ...prev[day], loading: true } }));
    try {
      const res = await fetch(dayPhotosUrl(targetRef.current, day, page));
      const data = await res.json();
      const { content, isLast } = normalizePage(data);
      setPhotosByDay(prev => ({ ...prev, [day]: [...(prev[day] || []), ...content] }));
      setDayPageState(prev => ({ ...prev, [day]: { nextPage: page + 1, isLast, loading: false } }));
    } catch (err) {
      console.error('Failed to load photos for day', day, err);
      setDayPageState(prev => ({ ...prev, [day]: { ...prev[day], loading: false } }));
    }
  }, []);

  // Used by App.jsx's refreshAndMaintainScroll for actions other than handleMoveToGarbage
  // (restore/move-to-main/permanent-delete/date-edit/edit-photo/unlink-duplicate) that still need
  // *some* way to make their effect visible on a day-indexed view, since none of them get
  // handleMoveToGarbage's local in-place update. Re-fetches page 0 for every day that already has
  // *something* loaded, replacing that day's content, and refreshes the day list/counts itself.
  // Trade-off: a day with more than one page loaded only gets its first page refreshed - a
  // narrower version of the scroll-losing problem handleMoveToGarbage was fixed for, but bounded
  // to one day's overflow rather than the whole feed, and only when that specific day had >100
  // photos loaded already.
  const refreshLoadedDays = useCallback(async () => {
    await refreshDays();
    const loadedDays = Object.keys(dayPageStateRef.current);
    await Promise.all(loadedDays.map(async (day) => {
      try {
        const res = await fetch(dayPhotosUrl(targetRef.current, day, 0));
        const data = await res.json();
        const { content, isLast } = normalizePage(data);
        setPhotosByDay(prev => ({ ...prev, [day]: content }));
        setDayPageState(prev => ({ ...prev, [day]: { nextPage: 1, isLast, loading: false } }));
      } catch (err) {
        console.error('Failed to refresh photos for day', day, err);
      }
    }));
  }, [refreshDays]);

  return { days, loadingDays, photosByDay, setPhotosByDay, dayPageState, loadDayPage, refreshDays, refreshLoadedDays };
}
