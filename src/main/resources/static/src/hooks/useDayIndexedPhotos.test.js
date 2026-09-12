import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { useDayIndexedPhotos, resolveDayIndexTarget } from './useDayIndexedPhotos';

describe('resolveDayIndexTarget', () => {
  it('resolves an album path', () => {
    expect(resolveDayIndexTarget('/albums/abc')).toEqual({ type: 'album', albumId: 'abc', spaceId: null, personId: null });
  });

  it('resolves a space path', () => {
    expect(resolveDayIndexTarget('/spaces/xyz')).toEqual({ type: 'space', albumId: null, spaceId: 'xyz', personId: null });
  });

  it('resolves a person path', () => {
    expect(resolveDayIndexTarget('/persons/abc123')).toEqual({ type: 'person', albumId: null, spaceId: null, personId: 'abc123' });
  });

  it('resolves the garbage path', () => {
    expect(resolveDayIndexTarget('/garbage')).toEqual({ type: 'garbage', albumId: null, spaceId: null, personId: null });
  });

  it('resolves a share-link path to a non-fetching share target', () => {
    expect(resolveDayIndexTarget('/share/abcmd5/holiday')).toEqual({ type: 'share', albumId: null, spaceId: null, personId: null });
    expect(resolveDayIndexTarget('/share/abcmd5/holiday/p/photo-1')).toEqual({ type: 'share', albumId: null, spaceId: null, personId: null });
  });

  it('defaults to library for everything else', () => {
    expect(resolveDayIndexTarget('/')).toEqual({ type: 'library', albumId: null, spaceId: null, personId: null });
  });
});

describe('useDayIndexedPhotos', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('fetches the day index up front for the Library path', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({ json: () => Promise.resolve([{ day: '2024-03-15', count: 3 }]) })
    );

    const { result } = renderHook(() => useDayIndexedPhotos('/'));

    await waitFor(() => expect(result.current.days).toHaveLength(1));
    expect(result.current.loadingDays).toBe(false);
    expect(global.fetch.mock.calls[0][0]).toBe('/api/photos/days');
  });

  it('fetches the album days endpoint for an album path', async () => {
    global.fetch = vi.fn(() => Promise.resolve({ json: () => Promise.resolve([]) }));

    renderHook(() => useDayIndexedPhotos('/albums/abc123'));

    await waitFor(() => expect(global.fetch).toHaveBeenCalled());
    expect(global.fetch.mock.calls[0][0]).toBe('/api/albums/abc123/days');
  });

  it('fetches the space days endpoint for a space path', async () => {
    global.fetch = vi.fn(() => Promise.resolve({ json: () => Promise.resolve([]) }));

    renderHook(() => useDayIndexedPhotos('/spaces/xyz'));

    await waitFor(() => expect(global.fetch).toHaveBeenCalled());
    expect(global.fetch.mock.calls[0][0]).toBe('/api/photos/space/xyz/days');
  });

  it('fetches the garbage days endpoint for the garbage path', async () => {
    global.fetch = vi.fn(() => Promise.resolve({ json: () => Promise.resolve([]) }));

    renderHook(() => useDayIndexedPhotos('/garbage'));

    await waitFor(() => expect(global.fetch).toHaveBeenCalled());
    expect(global.fetch.mock.calls[0][0]).toBe('/api/photos/garbage/days');
  });

  it('fetches the person days endpoint for a person path', async () => {
    global.fetch = vi.fn(() => Promise.resolve({ json: () => Promise.resolve([]) }));

    renderHook(() => useDayIndexedPhotos('/persons/abc123'));

    await waitFor(() => expect(global.fetch).toHaveBeenCalled());
    expect(global.fetch.mock.calls[0][0]).toBe('/api/faces/person/abc123/days');
  });

  it('makes no request on a share-link path before its album id is known (the App-level call)', async () => {
    global.fetch = vi.fn(() => Promise.resolve({ json: () => Promise.resolve([]) }));

    const { result } = renderHook(() => useDayIndexedPhotos('/share/abcmd5/holiday'));

    await waitFor(() => expect(result.current.loadingDays).toBe(false));
    expect(result.current.days).toEqual([]);
    expect(global.fetch).not.toHaveBeenCalled();

    // loadDayPage is also inert here (no per-day endpoint), so it must not throw or fetch.
    await act(async () => { await result.current.loadDayPage('2024-03-15'); });
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it('hits the guest share endpoints when given an explicit shareAlbum target (SharedAlbumView)', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ json: () => Promise.resolve([{ day: '2024-03-15', count: 2 }]) }) // days
      .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [{ id: 'a' }], page: { number: 0, totalPages: 1 } }) }); // page 0

    const shareTarget = { type: 'shareAlbum', albumId: 'album-9', spaceId: null, personId: null };
    const { result } = renderHook(() => useDayIndexedPhotos('/share/abcmd5/holiday', shareTarget));

    await waitFor(() => expect(result.current.days).toHaveLength(1));
    expect(global.fetch.mock.calls[0][0]).toBe('/api/albums/share/album-9/days');

    await act(async () => { await result.current.loadDayPage('2024-03-15'); });
    expect(global.fetch.mock.calls[1][0]).toBe('/api/albums/share/album-9/photos/day/2024-03-15?page=0&size=100');
    expect(result.current.photosByDay['2024-03-15']).toEqual([{ id: 'a' }]);
  });

  it('refetches when a share target resolves its album id (share -> shareAlbum)', async () => {
    global.fetch = vi.fn(() => Promise.resolve({ json: () => Promise.resolve([{ day: '2024-03-15', count: 1 }]) }));

    const pending = { type: 'share', albumId: null, spaceId: null, personId: null };
    const resolved = { type: 'shareAlbum', albumId: 'album-9', spaceId: null, personId: null };
    const { result, rerender } = renderHook(
      ({ target }) => useDayIndexedPhotos('/share/abcmd5/holiday', target),
      { initialProps: { target: pending } }
    );

    await waitFor(() => expect(result.current.loadingDays).toBe(false));
    expect(global.fetch).not.toHaveBeenCalled();

    rerender({ target: resolved });
    await waitFor(() => expect(result.current.days).toHaveLength(1));
    expect(global.fetch.mock.calls[0][0]).toBe('/api/albums/share/album-9/days');
  });

  // loadDayPage's page-tracking and isLast come from the response itself (not purely a
  // client-computed page count from the day index's own `count`), so a stale/drifted count
  // between the /days call and the actual per-day fetches doesn't strand the UI.
  it('loads a day\'s first page, tracks the next page number, and stops once isLast is true', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ json: () => Promise.resolve([{ day: '2024-03-15', count: 150 }]) }) // days
      .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [{ id: 'a' }], pageNumber: 0, pageSize: 100, totalElements: 150, totalPages: 2, last: false }) }) // page 0
      .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [{ id: 'b' }], pageNumber: 1, pageSize: 100, totalElements: 150, totalPages: 2, last: true }) }); // page 1

    const { result } = renderHook(() => useDayIndexedPhotos('/'));
    await waitFor(() => expect(result.current.days).toHaveLength(1));

    await act(async () => { await result.current.loadDayPage('2024-03-15'); });
    expect(global.fetch.mock.calls[1][0]).toBe('/api/photos/day/2024-03-15?page=0&size=100');
    expect(result.current.photosByDay['2024-03-15']).toEqual([{ id: 'a' }]);
    expect(result.current.dayPageState['2024-03-15'].isLast).toBe(false);

    await act(async () => { await result.current.loadDayPage('2024-03-15'); });
    expect(global.fetch.mock.calls[2][0]).toBe('/api/photos/day/2024-03-15?page=1&size=100');
    expect(result.current.photosByDay['2024-03-15']).toEqual([{ id: 'a' }, { id: 'b' }]);
    expect(result.current.dayPageState['2024-03-15'].isLast).toBe(true);

    // A third call should be a no-op (isLast already true) rather than an extra fetch.
    await act(async () => { await result.current.loadDayPage('2024-03-15'); });
    expect(global.fetch).toHaveBeenCalledTimes(3);
  });

  it('builds the garbage per-day photos URL', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ json: () => Promise.resolve([{ day: '2024-03-15', count: 1 }]) })
      .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [{ id: 'a' }], last: true }) });

    const { result } = renderHook(() => useDayIndexedPhotos('/garbage'));
    await waitFor(() => expect(result.current.days).toHaveLength(1));

    await act(async () => { await result.current.loadDayPage('2024-03-15'); });
    expect(global.fetch.mock.calls[1][0]).toBe('/api/photos/garbage/day/2024-03-15?page=0&size=100');
  });

  it('builds the person per-day photos URL', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ json: () => Promise.resolve([{ day: '2024-03-15', count: 1 }]) })
      .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [{ id: 'a' }], page: { size: 100, number: 0, totalElements: 1, totalPages: 1 } }) });

    const { result } = renderHook(() => useDayIndexedPhotos('/persons/abc123'));
    await waitFor(() => expect(result.current.days).toHaveLength(1));

    await act(async () => { await result.current.loadDayPage('2024-03-15'); });
    expect(global.fetch.mock.calls[1][0]).toBe('/api/faces/person/abc123/photos/day/2024-03-15?page=0&size=100');
  });

  // Album's per-day endpoint returns a raw Spring Data Page (Spring Boot 3.3+'s
  // {content, page:{...}} envelope, no top-level `last`) instead of this app's own PageResponse -
  // isLast must be derived from page.number/page.totalPages instead.
  it('derives isLast from the raw Page envelope (Album\'s per-day endpoint)', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ json: () => Promise.resolve([{ day: '2024-03-15', count: 1 }]) })
      .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [{ id: 'a' }], page: { size: 100, number: 0, totalElements: 1, totalPages: 1 } }) });

    const { result } = renderHook(() => useDayIndexedPhotos('/albums/abc'));
    await waitFor(() => expect(result.current.days).toHaveLength(1));

    await act(async () => { await result.current.loadDayPage('2024-03-15'); });
    expect(result.current.dayPageState['2024-03-15'].isLast).toBe(true);
  });

  // Regression test: App.jsx used to call this hook unconditionally, so on a cold load (or right
  // after logging in, since the targetKey doesn't change across the /login -> / navigation) the
  // days-fetch could race useAuth's own session check and hit the backend before the session was
  // recognized - with nothing ever retrying it afterwards. `enabled` fixes this by deferring the
  // fetch until the caller says auth is confirmed, then firing once it flips true.
  it('does not fetch while disabled, and fetches once enabled flips true', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({ json: () => Promise.resolve([{ day: '2024-03-15', count: 1 }]) })
    );

    const { result, rerender } = renderHook(
      ({ enabled }) => useDayIndexedPhotos('/', null, enabled),
      { initialProps: { enabled: false } }
    );

    await waitFor(() => expect(result.current.loadingDays).toBe(false));
    expect(result.current.days).toEqual([]);
    expect(global.fetch).not.toHaveBeenCalled();

    rerender({ enabled: true });
    await waitFor(() => expect(result.current.days).toHaveLength(1));
    expect(global.fetch.mock.calls[0][0]).toBe('/api/photos/days');
  });

  // Regression test for a real production report (2026-09-12): a person's Photos tab firing
  // several identical GET .../photos/day/{day} requests per day. Root cause: PhotoGrid's
  // day-sentinel IntersectionObserver can call loadDayPage for the SAME day more than once in one
  // synchronous burst (its own look-ahead prefetch landing on a day that's also directly
  // intersecting, and/or the observer recreating on every OTHER day's fetch completing and
  // re-evaluating still-visible sentinels) - all before a render has happened to sync
  // dayPageStateRef via its own effect, so the old ref-only guard couldn't see a fetch as
  // "in flight" until one render later. Every other test above always awaits one loadDayPage call
  // before making the next, which is exactly why this never surfaced there.
  it('does not double-fetch when loadDayPage is called twice for the same day before the first resolves', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ json: () => Promise.resolve([{ day: '2024-03-15', count: 1 }]) })
      .mockResolvedValue({ json: () => Promise.resolve({ content: [{ id: 'a' }], last: true }) });

    const { result } = renderHook(() => useDayIndexedPhotos('/persons/abc123'));
    await waitFor(() => expect(result.current.days).toHaveLength(1));

    await act(async () => {
      const first = result.current.loadDayPage('2024-03-15');
      const second = result.current.loadDayPage('2024-03-15'); // same synchronous burst, no await between them
      await Promise.all([first, second]);
    });

    const dayPhotoCalls = global.fetch.mock.calls.filter(call => call[0].includes('/photos/day/2024-03-15'));
    expect(dayPhotoCalls).toHaveLength(1);
    expect(result.current.photosByDay['2024-03-15']).toEqual([{ id: 'a' }]);
  });

  it('resets photosByDay/dayPageState and refetches the day index when the path changes', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ json: () => Promise.resolve([{ day: '2024-03-15', count: 1 }]) })
      .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [{ id: 'a' }], last: true }) })
      .mockResolvedValueOnce({ json: () => Promise.resolve([{ day: '2024-04-01', count: 1 }]) });

    const { result, rerender } = renderHook(({ path }) => useDayIndexedPhotos(path), { initialProps: { path: '/' } });
    await waitFor(() => expect(result.current.days).toHaveLength(1));

    await act(async () => { await result.current.loadDayPage('2024-03-15'); });
    expect(result.current.photosByDay['2024-03-15']).toEqual([{ id: 'a' }]);

    rerender({ path: '/albums/def' });

    await waitFor(() => expect(result.current.days).toEqual([{ day: '2024-04-01', count: 1 }]));
    expect(result.current.photosByDay).toEqual({});
    expect(result.current.dayPageState).toEqual({});
  });

  describe('mediaType filter', () => {
    it('does not append ?type= for the default "all" filter (pre-filter callers unaffected)', async () => {
      global.fetch = vi.fn(() => Promise.resolve({ json: () => Promise.resolve([]) }));

      renderHook(() => useDayIndexedPhotos('/'));

      await waitFor(() => expect(global.fetch).toHaveBeenCalled());
      expect(global.fetch.mock.calls[0][0]).toBe('/api/photos/days');
    });

    it('appends ?type=video to the Library days and day-photos URLs', async () => {
      global.fetch = vi.fn()
        .mockResolvedValueOnce({ json: () => Promise.resolve([{ day: '2024-03-15', count: 1 }]) })
        .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [{ id: 'a' }], last: true }) });

      const { result } = renderHook(() => useDayIndexedPhotos('/', null, true, 'video'));
      await waitFor(() => expect(result.current.days).toHaveLength(1));
      expect(global.fetch.mock.calls[0][0]).toBe('/api/photos/days?type=video');

      await act(async () => { await result.current.loadDayPage('2024-03-15'); });
      expect(global.fetch.mock.calls[1][0]).toBe('/api/photos/day/2024-03-15?type=video&page=0&size=100');
    });

    it('appends ?type=photo to Space and Garbage URLs', async () => {
      global.fetch = vi.fn()
        .mockResolvedValueOnce({ json: () => Promise.resolve([{ day: '2024-03-15', count: 1 }]) })
        .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [], last: true }) });
      renderHook(() => useDayIndexedPhotos('/spaces/xyz', null, true, 'photo'));
      await waitFor(() => expect(global.fetch).toHaveBeenCalled());
      expect(global.fetch.mock.calls[0][0]).toBe('/api/photos/space/xyz/days?type=photo');

      global.fetch = vi.fn(() => Promise.resolve({ json: () => Promise.resolve([]) }));
      renderHook(() => useDayIndexedPhotos('/garbage', null, true, 'photo'));
      await waitFor(() => expect(global.fetch).toHaveBeenCalled());
      expect(global.fetch.mock.calls[0][0]).toBe('/api/photos/garbage/days?type=photo');
    });

    it('appends ?type= to the Album days/day-photos URLs too', async () => {
      global.fetch = vi.fn()
        .mockResolvedValueOnce({ json: () => Promise.resolve([{ day: '2024-03-15', count: 1 }]) })
        .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [], page: { size: 100, number: 0, totalElements: 0, totalPages: 1 } }) });
      const { result } = renderHook(() => useDayIndexedPhotos('/albums/abc', null, true, 'video'));
      await waitFor(() => expect(result.current.days).toHaveLength(1));
      expect(global.fetch.mock.calls[0][0]).toBe('/api/albums/abc/days?type=video');

      await act(async () => { await result.current.loadDayPage('2024-03-15'); });
      expect(global.fetch.mock.calls[1][0]).toBe('/api/albums/abc/photos/day/2024-03-15?type=video&page=0&size=100');
    });

    it('does not append ?type= for Person or the guest share flow, which do not expose the filter', async () => {
      global.fetch = vi.fn(() => Promise.resolve({ json: () => Promise.resolve([]) }));
      renderHook(() => useDayIndexedPhotos('/persons/abc123', null, true, 'video'));
      await waitFor(() => expect(global.fetch).toHaveBeenCalled());
      expect(global.fetch.mock.calls[0][0]).toBe('/api/faces/person/abc123/days');

      global.fetch = vi.fn()
        .mockResolvedValueOnce({ json: () => Promise.resolve([{ day: '2024-03-15', count: 1 }]) });
      const shareTarget = { type: 'shareAlbum', albumId: 'album-9', spaceId: null, personId: null };
      renderHook(() => useDayIndexedPhotos('/share/abcmd5/holiday', shareTarget, true, 'video'));
      await waitFor(() => expect(global.fetch).toHaveBeenCalled());
      expect(global.fetch.mock.calls[0][0]).toBe('/api/albums/share/album-9/days');
    });

    it('resets and refetches when only the mediaType filter changes', async () => {
      global.fetch = vi.fn()
        .mockResolvedValueOnce({ json: () => Promise.resolve([{ day: '2024-03-15', count: 3 }]) })
        .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [{ id: 'a' }], last: true }) })
        .mockResolvedValueOnce({ json: () => Promise.resolve([{ day: '2024-03-15', count: 1 }]) });

      const { result, rerender } = renderHook(
        ({ mediaType }) => useDayIndexedPhotos('/', null, true, mediaType),
        { initialProps: { mediaType: 'all' } }
      );
      await waitFor(() => expect(result.current.days).toEqual([{ day: '2024-03-15', count: 3 }]));

      await act(async () => { await result.current.loadDayPage('2024-03-15'); });
      expect(result.current.photosByDay['2024-03-15']).toEqual([{ id: 'a' }]);

      rerender({ mediaType: 'video' });

      await waitFor(() => expect(result.current.days).toEqual([{ day: '2024-03-15', count: 1 }]));
      expect(global.fetch.mock.calls[2][0]).toBe('/api/photos/days?type=video');
      expect(result.current.photosByDay).toEqual({});
      expect(result.current.dayPageState).toEqual({});
    });
  });
});
