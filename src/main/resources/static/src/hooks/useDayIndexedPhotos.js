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
  // Public share-link routes (/share/:userMd5/:name[/p/:photoId]) render SharedAlbumView. This
  // App-level hook still runs on that route, but the album id isn't in the path (SharedAlbumView
  // resolves it from /api/share/:md5/:name first), so there's nothing to target here - give it a
  // no-fetch 'share' target rather than letting it fall through to the library default and fire
  // /api/photos/days, which a ROLE_SHARE guest can't call. SharedAlbumView itself calls this hook
  // a second time with an explicit { type: 'shareAlbum', albumId } once it has the id.
  if (effectivePath.startsWith('/share/')) return { type: 'share', albumId: null, spaceId: null, personId: null };
  return { type: 'library', albumId: null, spaceId: null, personId: null };
}

// Appends the photo/video type filter (mediaType: 'all' | 'photo' | 'video') to a day-index URL -
// 'all' is the pre-filter default and adds nothing, so every existing caller/test that never
// passes mediaType keeps hitting the exact same URLs.
function withMediaType(url, mediaType) {
  if (!url || !mediaType || mediaType === 'all') return url;
  return `${url}${url.includes('?') ? '&' : '?'}type=${mediaType}`;
}

// Returns null for targets with no day-index endpoint yet (the share route before its album id is
// known) - callers must skip the fetch entirely when this is null.
function daysUrl({ type, albumId, spaceId, personId }, mediaType) {
  if (type === 'album') return withMediaType(`/api/albums/${albumId}/days`, mediaType);
  if (type === 'shareAlbum') return `/api/albums/share/${albumId}/days`;
  if (type === 'space') return withMediaType(`/api/photos/space/${spaceId}/days`, mediaType);
  if (type === 'person') return `/api/faces/person/${personId}/days`;
  if (type === 'garbage') return withMediaType('/api/photos/garbage/days', mediaType);
  if (type === 'share') return null;
  return withMediaType('/api/photos/days', mediaType);
}

function dayPhotosUrl({ type, albumId, spaceId, personId }, day, page, mediaType) {
  if (type === 'share') return null;
  const base = type === 'album'
    ? withMediaType(`/api/albums/${albumId}/photos/day/${day}`, mediaType)
    : type === 'shareAlbum'
      ? `/api/albums/share/${albumId}/photos/day/${day}`
      : type === 'space'
        ? withMediaType(`/api/photos/space/${spaceId}/day/${day}`, mediaType)
        : type === 'person'
          ? `/api/faces/person/${personId}/photos/day/${day}`
          : type === 'garbage'
            ? withMediaType(`/api/photos/garbage/day/${day}`, mediaType)
            : withMediaType(`/api/photos/day/${day}`, mediaType);
  return `${base}${base.includes('?') ? '&' : '?'}page=${page}&size=${PAGE_SIZE}`;
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

// Day-index browsing for every photo-listing view (Library/Album/Space/Garbage/Person, plus the
// guest SharedAlbumView): fetch every day that has a photo up front (cheap - just a count + cover
// per day), then lazily fetch each day's actual photos (paginated, 100/page) only once that day's
// section scrolls into view. Replaces the old flat, page-number-paginated usePhotoData.js hook
// entirely (deleted).
//
// `explicitTarget`, when given, overrides the path-derived target - SharedAlbumView uses it to
// point at { type: 'shareAlbum', albumId } once it has resolved the share's album id (the id isn't
// in the route path). Pass a value with a stable identity (useMemo it) - though the reset effect
// keys on a derived string, not the object, so an unstable one won't loop.
//
// `enabled` (default true) gates the actual fetching. App.jsx passes its own `isAuthenticated`
// here: without this, the days-fetch used to fire on mount regardless of auth state, racing
// useAuth's own /api/user/me check - on a cold load (or right after logging in, since the
// targetKey doesn't change across the /login -> / navigation and so the reset effect never
// re-ran), that request could hit the backend before the session was recognized, get back a 401
// with an empty body, and fail `res.json()` with "Unexpected end of JSON input" - permanently
// (nothing ever retried it), until a full page refresh re-ran everything from scratch with the
// session already valid. Toggling `enabled` false->true re-runs the reset effect below, so the
// real fetch only ever happens once auth is confirmed.
// `mediaType` ('all' | 'photo' | 'video', default 'all') is the Library/Space/Album/Garbage
// photo-video filter - Person and the guest share view don't expose it (see daysUrl/dayPhotosUrl
// above, which only thread it through for those four target types).
export function useDayIndexedPhotos(effectivePath, explicitTarget = null, enabled = true, mediaType = 'all') {
  const [days, setDays] = useState([]);
  const [loadingDays, setLoadingDays] = useState(true);
  const [photosByDay, setPhotosByDay] = useState({});
  const [dayPageState, setDayPageState] = useState({});

  // Kept current every render (cheap, pure regex match) so loadDayPage/refreshDays can read the
  // latest target without needing it in their own dependency arrays.
  const resolveTarget = () => explicitTarget || resolveDayIndexTarget(effectivePath);
  const targetRef = useRef(resolveTarget());
  targetRef.current = resolveTarget();

  // Same ref idiom as targetRef above, so loadDayPage/refreshDays/refreshLoadedDays can read the
  // latest filter without it in their own dependency arrays.
  const mediaTypeRef = useRef(mediaType);
  mediaTypeRef.current = mediaType;

  // Stable string identity for the current target - drives the reset-and-refetch effect below in
  // place of the raw effectivePath (which doesn't change when only explicitTarget does). Includes
  // mediaType so switching the filter resets and refetches too.
  const t = targetRef.current;
  const targetKey = `${t.type}:${t.albumId || t.spaceId || t.personId || ''}:${mediaType}`;

  // Mirrors usePhotoData.js's photosRef idiom: loadDayPage reads this instead of closing over
  // dayPageState, so its identity stays stable across renders.
  const dayPageStateRef = useRef(dayPageState);
  useEffect(() => { dayPageStateRef.current = dayPageState; }, [dayPageState]);

  // Separate from dayPageStateRef above on purpose: that ref only catches up to React state via
  // this OWN effect, one render behind - fine for a single caller, but PhotoGrid's day-sentinel
  // IntersectionObserver can call loadDayPage for the SAME day more than once in the same
  // synchronous burst (its own "look-ahead" prefetch landing on a day that's also directly
  // intersecting, and/or the observer tearing down/recreating on every OTHER day's fetch
  // completing and re-evaluating still-visible sentinels) - all before a render has happened to
  // sync dayPageStateRef. Real production symptom: a person's Photos tab firing several identical
  // GET .../photos/day/{day} requests per day (2026-09-12, user report). A plain Set mutated
  // synchronously the instant a fetch starts closes that window completely - no render/effect
  // round-trip needed for the very next call to see it.
  const inFlightDaysRef = useRef(new Set());

  const refreshDays = useCallback(async () => {
    const url = daysUrl(targetRef.current, mediaTypeRef.current);
    if (!url) {
      // Share-link route: SharedAlbumView owns the day index. Nothing consumes this hook there.
      setDays([]);
      setLoadingDays(false);
      return;
    }
    setLoadingDays(true);
    try {
      const res = await fetch(url);
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
    if (!enabled) {
      setDays([]);
      setLoadingDays(false);
      return;
    }
    refreshDays();
    // refreshDays is stable (reads targetRef, not a closure); this effect should only re-run when
    // the target itself changes (path navigation or SharedAlbumView resolving its album id) or
    // `enabled` flips (e.g. auth finishes confirming).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [targetKey, enabled]);

  const loadDayPage = useCallback(async (day) => {
    // inFlightDaysRef check-and-add first, and synchronously - see its own field comment. The
    // dayPageStateRef checks below still matter (a day already fully loaded, or mid-load from a
    // call outside this same synchronous burst, both need to keep being skipped).
    if (inFlightDaysRef.current.has(day)) return;
    const current = dayPageStateRef.current[day];
    if (current?.loading || current?.isLast) return;
    const page = current?.nextPage ?? 0;

    const url = dayPhotosUrl(targetRef.current, day, page, mediaTypeRef.current);
    if (!url) return; // share-link route - no per-day endpoint here (see resolveDayIndexTarget)

    inFlightDaysRef.current.add(day);
    setDayPageState(prev => ({ ...prev, [day]: { ...prev[day], loading: true } }));
    try {
      const res = await fetch(url);
      const data = await res.json();
      const { content, isLast } = normalizePage(data);
      setPhotosByDay(prev => ({ ...prev, [day]: [...(prev[day] || []), ...content] }));
      setDayPageState(prev => ({ ...prev, [day]: { nextPage: page + 1, isLast, loading: false } }));
    } catch (err) {
      console.error('Failed to load photos for day', day, err);
      setDayPageState(prev => ({ ...prev, [day]: { ...prev[day], loading: false } }));
    } finally {
      inFlightDaysRef.current.delete(day);
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
      const url = dayPhotosUrl(targetRef.current, day, 0, mediaTypeRef.current);
      if (!url) return;
      try {
        const res = await fetch(url);
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
