import { useState, useEffect, useRef, useCallback } from 'react';
import { useLocale } from '../LocaleContext.jsx';

function resolveEndpoint(type, albumId, spaceId, personId) {
  if (type === 'garbage') return { endpoint: '/api/photos/garbage', sort: 'garbageAt,desc' };
  if (type === 'album' && albumId) return { endpoint: `/api/albums/${albumId}/photos`, sort: 'dateTaken,desc' };
  if (type === 'space' && spaceId) return { endpoint: `/api/spaces/${spaceId}/photos`, sort: 'dateTaken,desc' };
  if (type === 'person' && personId) return { endpoint: `/api/faces/person/${personId}/photos`, sort: 'dateTaken,desc' };
  return { endpoint: '/api/photos', sort: 'dateTaken,desc' };
}

export function usePhotoData(effectivePath, isLightboxOpen) {
  const { messages } = useLocale();
  const [photos, setPhotos] = useState([]);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(true);
  // Only ever true after jumpToMonth (LibraryTimeline.jsx) — whether there's anything newer than
  // what's currently loaded, i.e. whether back-scrolling (loadNewer below) has anything to do.
  // Always false for plain top-of-Library/Album/Space/Person/Garbage browsing, since there's
  // nothing newer than page 0 of the normal feed by definition.
  const [hasNewer, setHasNewer] = useState(false);
  const loadedPathRef = useRef(null);
  // True once jumpToMonth has been called for the CURRENT view — switches the page>0 effect below
  // from plain numeric paging over to cursor-based loadOlder (see that effect for why: numeric
  // page N stops meaning anything once the array's start has shifted via jumpToMonth or loadNewer
  // prepends). Reset alongside the rest of the view's state whenever effectivePath changes.
  const jumpedRef = useRef(false);
  // Mirrors `photos` for loadOlder/loadNewer to read the current first/last item from without
  // needing `photos` itself in their useCallback deps — keeps their identity stable across
  // fetches, which matters because they're referenced from the page-change effect below and an
  // identity that churned every fetch would make that effect's dependency list meaningless.
  const photosRef = useRef(photos);
  useEffect(() => { photosRef.current = photos; }, [photos]);

  // The actual network call, decoupled from touching `photos`/`hasMore` state — fetchPhotos below
  // (the plain numeric-page flow) and loadOlder/loadNewer (the cursor flow) each apply the result
  // to state differently (append vs. reset vs. prepend), so they share this instead of duplicating
  // the fetch/response-shape logic three times.
  const fetchPhotosRaw = useCallback(async (pageNum, type = 'library', albumId = null, spaceId = null, personId = null, cursorParams = {}) => {
    const { endpoint, sort } = resolveEndpoint(type, albumId, spaceId, personId);
    let url = `${endpoint}?page=${pageNum}&size=20&sort=${sort}`;
    // Only meaningful on the plain Library feed (LibraryTimeline.jsx/loadOlder/loadNewer are the
    // only callers that ever set these) — other endpoints don't understand them and just ignore
    // the extra params.
    const { jumpToMonth, beforeDate, afterDate } = cursorParams;
    if (jumpToMonth) url += `&jumpToMonth=${encodeURIComponent(jumpToMonth)}`;
    if (beforeDate) url += `&beforeDate=${encodeURIComponent(beforeDate)}`;
    if (afterDate) url += `&afterDate=${encodeURIComponent(afterDate)}`;

    const res = await fetch(url);
    const data = await res.json();

    const content = Array.isArray(data) ? data : (data.content || []);
    const isLast = Array.isArray(data) ? true : (data.last === undefined ? true : data.last);
    return { content, isLast };
  }, []);

  const fetchPhotos = useCallback(async (pageNum, reset = false, type = 'library', albumId = null, spaceId = null, personId = null, cursorParams = {}) => {
    try {
      const { content, isLast } = await fetchPhotosRaw(pageNum, type, albumId, spaceId, personId, cursorParams);
      setPhotos(prev => (reset ? content : [...prev, ...content]));
      setHasMore(!isLast);
      if (reset) {
        setPage(0);
      }
    } catch (err) {
      console.error(messages["fetchPhotosFailed"], err);
    }
  }, [fetchPhotosRaw, messages]);

  // Continues loading OLDER photos with an exact per-photo cursor instead of a page number — the
  // mechanism the page>0 effect below switches to once jumpToMonth has been used (see there for
  // why page numbers alone stop being reliable at that point).
  const loadOlder = useCallback(async () => {
    const oldest = photosRef.current[photosRef.current.length - 1];
    if (!oldest?.fullDateTime) return;
    try {
      const { content, isLast } = await fetchPhotosRaw(0, 'library', null, null, null, { beforeDate: oldest.fullDateTime });
      setPhotos(prev => [...prev, ...content]);
      setHasMore(!isLast);
    } catch (err) {
      console.error(messages["fetchPhotosFailed"], err);
    }
  }, [fetchPhotosRaw, messages]);

  // Loads NEWER photos above what's currently loaded — back-scrolling past a jumpToMonth landing
  // point. Cursored off the currently-first photo's own dateTaken (its `fullDateTime`, which is
  // already ISO_LOCAL_DATE_TIME — see PhotoMapper — so it round-trips to the backend's
  // LocalDateTime.parse verbatim, no reformatting needed). Prepends rather than appends; the
  // scroll-position compensation for that (so the view doesn't visually jump) lives in
  // PhotoGrid.jsx, which is the one with access to the actual scrolling container.
  const loadNewer = useCallback(async () => {
    const newest = photosRef.current[0];
    if (!newest?.fullDateTime) return;
    try {
      const { content, isLast } = await fetchPhotosRaw(0, 'library', null, null, null, { afterDate: newest.fullDateTime });
      setPhotos(prev => [...content, ...prev]);
      setHasNewer(!isLast);
    } catch (err) {
      console.error(messages["fetchPhotosFailed"], err);
    }
  }, [fetchPhotosRaw, messages]);

  const loadPhotos = useCallback((page, reset) => {
    // Share/guest views (/share/**) fetch their own photos via useSharedAlbumPhotos against a
    // separate, guest-scoped endpoint — none of this hook's authenticated /api/photos* endpoints
    // apply there (they'd 401/500 for a guest session), so there's nothing for it to do.
    if (effectivePath.startsWith('/share')) {
      return;
    }

    const albumMatch = effectivePath.match(/^\/albums\/(.+)$/);
    const spaceMatch = effectivePath.match(/^\/spaces\/(.+)$/);
    const personMatch = effectivePath.match(/^\/persons\/(.+)$/);

    let type = 'library', albumId = null, spaceId = null, personId = null;

    if (effectivePath === '/') {
        type = 'library';
    } else if (effectivePath === '/garbage') {
        type = 'garbage';
    } else if (albumMatch) {
        type = 'album';
        albumId = albumMatch[1];
    } else if (spaceMatch) {
        type = 'space';
        spaceId = spaceMatch[1];
    } else if (personMatch) {
        type = 'person';
        personId = personMatch[1];
    }

    fetchPhotos(page, reset, type, albumId, spaceId, personId);
  }, [effectivePath, fetchPhotos]);

  useEffect(() => {
    if (isLightboxOpen) return;

    // Only (re)load from page 0 when the view actually changed, or this is the very first
    // load for it (e.g. landing directly on an /asset/:id URL before the grid ever loaded,
    // where the load was deferred while the Lightbox was open). Do NOT reset just because the
    // Lightbox closed on the same view — the background grid is still valid and may already
    // have several pages loaded; resetting here used to silently drop all of them, requiring
    // the user to scroll back down to re-trigger loading past page 0.
    if (loadedPathRef.current === effectivePath) return;
    loadedPathRef.current = effectivePath;
    jumpedRef.current = false;
    setHasNewer(false);

    const isDuplicates = effectivePath.startsWith('/duplicates');
    loadPhotos(0, !isDuplicates);
  }, [effectivePath, loadPhotos, isLightboxOpen]);

  // Continues loading OLDER photos (PhotoGrid.jsx's bottom sentinel calling setPage). Before any
  // jump this is plain numeric-page paging, same as always. After jumpToMonth, page numbers stop
  // meaning anything (page N doesn't correspond to a consistent slice once loadNewer has prepended
  // an unknown number of items above it), so this switches to loadOlder's date-cursor paging
  // instead — same trigger (setPage), different mechanism underneath.
  useEffect(() => {
    if (page === 0) return;
    if (jumpedRef.current) {
      loadOlder();
    } else {
      loadPhotos(page, false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- loadOlder/loadPhotos intentionally
    // omitted: both are stable across renders (loadOlder reads photosRef instead of closing over
    // `photos`; loadPhotos's own deps are stable), so including them would add nothing but noise —
    // this effect should only ever re-run because `page` changed.
  }, [page]);

  // Jumps the Library feed straight to a given calendar month (LibraryTimeline.jsx's Memories
  // cards and browse-by-month list) — resets the grid to start there, the same way the initial
  // load works, rather than paging through everything in between. `hasNewer` is set optimistically
  // true (there's very likely something newer than any month a user would deliberately jump to);
  // if that's wrong (jumping to the single newest month), the first loadNewer call self-corrects
  // it to false immediately, at the cost of one fetch that returns nothing.
  const jumpToMonth = useCallback((year, month) => {
    jumpedRef.current = true;
    setHasNewer(true);
    const monthStr = `${year}-${String(month).padStart(2, '0')}`;
    fetchPhotos(0, true, 'library', null, null, null, { jumpToMonth: monthStr });
  }, [fetchPhotos]);

  return { photos, setPhotos, page, setPage, hasMore, hasNewer, fetchPhotos, jumpToMonth, loadNewer };
}
