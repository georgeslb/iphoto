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
});
