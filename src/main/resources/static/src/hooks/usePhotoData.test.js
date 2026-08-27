import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { usePhotoData } from './usePhotoData';
import { localeWrapper } from '../test/testUtils.jsx';

describe('usePhotoData', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('sets hasMore from a paginated response (content/last)', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({ json: () => Promise.resolve({ content: [{ id: '1' }], last: false }) })
    );

    const { result } = renderHook(() => usePhotoData('/', false), { wrapper: localeWrapper });

    await waitFor(() => expect(result.current.photos).toHaveLength(1));
    expect(result.current.hasMore).toBe(true);
  });

  it('sets hasMore to false when the backend reports the last page', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({ json: () => Promise.resolve({ content: [{ id: '1' }], last: true }) })
    );

    const { result } = renderHook(() => usePhotoData('/', false), { wrapper: localeWrapper });

    await waitFor(() => expect(result.current.photos).toHaveLength(1));
    expect(result.current.hasMore).toBe(false);
  });

  // Regression: /api/albums/{id}/photos used to return a bare array with no
  // pagination metadata at all (instead of a paginated object like /api/photos and
  // /api/spaces/{id}/photos), and usePhotoData treats a bare array as "always the
  // last page". This locks that fallback behavior in explicitly, so if any endpoint
  // regresses back to returning a bare array, infinite scroll silently breaking
  // again gets caught here instead of requiring manual discovery.
  it('treats a bare array response as a single, final page', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({ json: () => Promise.resolve([{ id: '1' }, { id: '2' }]) })
    );

    const { result } = renderHook(() => usePhotoData('/albums/abc', false), { wrapper: localeWrapper });

    await waitFor(() => expect(result.current.photos).toHaveLength(2));
    expect(result.current.hasMore).toBe(false);
  });

  it('fetches the album endpoint for an album path', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({ json: () => Promise.resolve({ content: [], last: true }) })
    );

    renderHook(() => usePhotoData('/albums/abc123', false), { wrapper: localeWrapper });

    await waitFor(() => expect(global.fetch).toHaveBeenCalled());
    expect(global.fetch.mock.calls[0][0]).toContain('/api/albums/abc123/photos');
  });

  // Contract from specification.md: photo list menus auto-fetch the next page
  // (infinite scroll drives this by calling setPage).
  it('fetches the next page automatically when setPage is called', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({ json: () => Promise.resolve({ content: [{ id: '1' }], last: false }) })
    );

    const { result } = renderHook(() => usePhotoData('/', false), { wrapper: localeWrapper });

    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(1));

    act(() => {
      result.current.setPage(p => p + 1);
    });

    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(2));
    expect(global.fetch.mock.calls[1][0]).toContain('page=1');
  });

  // Regression: closing the Lightbox used to re-trigger a page-0 reset fetch (isLightboxOpen
  // was a dependency of the load-on-path-change effect), silently discarding any pages loaded
  // past page 0 — visible as "only the first two pages show up, I have to scroll to reload"
  // after viewing a photo from page 2+ and closing the Lightbox.
  it('does not reset the loaded photos when the Lightbox merely closes on the same view', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({ json: () => Promise.resolve({ content: [{ id: '1' }], last: false }) })
    );

    const { result, rerender } = renderHook(
      ({ path, lightboxOpen }) => usePhotoData(path, lightboxOpen),
      { wrapper: localeWrapper, initialProps: { path: '/', lightboxOpen: false } }
    );

    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(1));

    act(() => { result.current.setPage(p => p + 1); });
    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(result.current.photos).toHaveLength(2));

    // Open then close the Lightbox without the underlying view changing.
    rerender({ path: '/', lightboxOpen: true });
    rerender({ path: '/', lightboxOpen: false });

    expect(global.fetch).toHaveBeenCalledTimes(2);
    expect(result.current.photos).toHaveLength(2);
  });

  // The deferred-load case the isLightboxOpen guard exists for: landing directly on an
  // /asset/:id URL before the grid has ever loaded. Closing the Lightbox should still trigger
  // the first load in that case.
  it('loads photos once the Lightbox closes, when landing directly on an asset URL', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({ json: () => Promise.resolve({ content: [{ id: '1' }], last: true }) })
    );

    const { result, rerender } = renderHook(
      ({ path, lightboxOpen }) => usePhotoData(path, lightboxOpen),
      { wrapper: localeWrapper, initialProps: { path: '/', lightboxOpen: true } }
    );

    expect(global.fetch).not.toHaveBeenCalled();

    rerender({ path: '/', lightboxOpen: false });

    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(1));
    expect(result.current.photos).toHaveLength(1);
  });

  // Regression: navigating to a /share/** guest route used to still fall through to the
  // default 'library' branch and fetch /api/photos?page=0&size=20&sort=dateTaken,desc — an
  // authenticated-only endpoint that's meaningless (and fails) for a guest session. Share views
  // fetch their own photos via useSharedAlbumPhotos against a separate endpoint, so this hook
  // should do nothing at all on a /share/** path.
  it('does not fetch anything for a /share/** path', async () => {
    global.fetch = vi.fn();

    renderHook(() => usePhotoData('/share/abc123/vacation', false), { wrapper: localeWrapper });

    await new Promise(resolve => setTimeout(resolve, 0));
    expect(global.fetch).not.toHaveBeenCalled();
  });

  // jumpToMonth (LibraryTimeline.jsx's Memories cards / browse-by-month list) resets the grid
  // to page 0 and passes the target month through as the jumpToMonth query param, the same way
  // the reset-on-path-change load already resets it.
  it('jumpToMonth resets to page 0 and requests the given month', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({ json: () => Promise.resolve({ content: [{ id: '1' }], last: true }) })
    );

    const { result } = renderHook(() => usePhotoData('/', false), { wrapper: localeWrapper });

    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(1));

    act(() => { result.current.setPage(p => p + 1); });
    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(2));

    act(() => { result.current.jumpToMonth(2025, 3); });

    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(3));
    expect(global.fetch.mock.calls[2][0]).toContain('page=0');
    expect(global.fetch.mock.calls[2][0]).toContain('jumpToMonth=2025-03');
    expect(result.current.page).toBe(0);
  });

  // Back-scrolling, older direction: once jumpToMonth has been used, page numbers stop
  // corresponding to a consistent slice (loadNewer below can prepend an unknown number of items
  // above them), so continued "load more" (setPage) must switch to an exact per-photo cursor
  // (`beforeDate`, the oldest loaded photo's own fullDateTime) instead of an incrementing page
  // number — this locks that switch in.
  it('uses a beforeDate cursor (not a page number) to load older photos after a jump', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [{ id: '1' }], last: true }) }) // initial load
      .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [{ id: 'm', fullDateTime: '2025-03-15T10:00:00' }], last: true }) }) // jump
      .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [{ id: 'older', fullDateTime: '2025-03-01T09:00:00' }], last: true }) }); // load older

    const { result } = renderHook(() => usePhotoData('/', false), { wrapper: localeWrapper });
    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(1));

    act(() => { result.current.jumpToMonth(2025, 3); });
    await waitFor(() => expect(result.current.photos).toEqual([{ id: 'm', fullDateTime: '2025-03-15T10:00:00' }]));

    act(() => { result.current.setPage(p => p + 1); });

    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(3));
    expect(global.fetch.mock.calls[2][0]).toContain('page=0');
    expect(global.fetch.mock.calls[2][0]).toContain(`beforeDate=${encodeURIComponent('2025-03-15T10:00:00')}`);
    await waitFor(() => expect(result.current.photos).toHaveLength(2));
    expect(result.current.photos[1].id).toBe('older');
  });

  // Back-scrolling, newer direction: loadNewer cursors off the currently-FIRST photo's
  // fullDateTime, and prepends (not appends) the result.
  it('loadNewer cursors off the first photo and prepends the result', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [{ id: '1' }], last: true }) }) // initial load
      .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [{ id: 'm', fullDateTime: '2025-03-15T10:00:00' }], last: true }) }) // jump
      .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [{ id: 'newer', fullDateTime: '2025-04-01T09:00:00' }], last: false }) }); // loadNewer

    const { result } = renderHook(() => usePhotoData('/', false), { wrapper: localeWrapper });
    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(1));

    act(() => { result.current.jumpToMonth(2025, 3); });
    await waitFor(() => expect(result.current.photos).toEqual([{ id: 'm', fullDateTime: '2025-03-15T10:00:00' }]));
    expect(result.current.hasNewer).toBe(true);

    await act(async () => { await result.current.loadNewer(); });

    expect(global.fetch.mock.calls[2][0]).toContain(`afterDate=${encodeURIComponent('2025-03-15T10:00:00')}`);
    expect(result.current.photos.map(p => p.id)).toEqual(['newer', 'm']);
    expect(result.current.hasNewer).toBe(true); // last: false -> still more above
  });
});
