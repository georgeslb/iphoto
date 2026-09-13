import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { useRecentPhotos } from './useRecentPhotos';

const BASE_URL = '/api/photos/recent';

describe('useRecentPhotos', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('fetches nothing until loadMore is called (lazy, opt-in fetch)', () => {
    global.fetch = vi.fn();
    const { result } = renderHook(() => useRecentPhotos(BASE_URL));

    expect(global.fetch).not.toHaveBeenCalled();
    expect(result.current.photos).toEqual([]);
    expect(result.current.hasLoaded).toBe(false);
  });

  it('loadMore fetches page 0 first, then appends subsequent pages', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [{ id: 'a' }], last: false }) })
      .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [{ id: 'b' }], last: true }) });

    const { result } = renderHook(() => useRecentPhotos(BASE_URL));

    await act(async () => { await result.current.loadMore(); });
    expect(global.fetch.mock.calls[0][0]).toBe(`${BASE_URL}?page=0`);
    expect(result.current.photos).toEqual([{ id: 'a' }]);
    expect(result.current.isLast).toBe(false);
    expect(result.current.hasLoaded).toBe(true);

    await act(async () => { await result.current.loadMore(); });
    expect(global.fetch.mock.calls[1][0]).toBe(`${BASE_URL}?page=1`);
    expect(result.current.photos).toEqual([{ id: 'a' }, { id: 'b' }]);
    expect(result.current.isLast).toBe(true);
  });

  it('is a no-op once isLast is true, and does not double-fetch while already loading', async () => {
    let resolveFirst;
    global.fetch = vi.fn()
      .mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve; }))
      .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [{ id: 'x' }], last: true }) });

    const { result } = renderHook(() => useRecentPhotos(BASE_URL));

    // Fire two overlapping calls before the first resolves - the second must not start a fetch of
    // its own (loadingRef guards it), matching the horizontal-scroll handler firing repeatedly.
    let firstCall;
    act(() => { firstCall = result.current.loadMore(); });
    await act(async () => { await result.current.loadMore(); });
    expect(global.fetch).toHaveBeenCalledTimes(1);

    resolveFirst({ json: () => Promise.resolve({ content: [{ id: 'x' }], last: true }) });
    await act(async () => { await firstCall; });
    expect(result.current.photos).toEqual([{ id: 'x' }]);
    expect(result.current.isLast).toBe(true);

    // Now that isLast is true, a further call must not fetch again either.
    await act(async () => { await result.current.loadMore(); });
    expect(global.fetch).toHaveBeenCalledTimes(1);
  });

  it('resets its state when baseUrl changes (e.g. navigating to a different Space)', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ json: () => Promise.resolve({ content: [{ id: 'a' }], last: true }) });

    const { result, rerender } = renderHook(
      ({ url }) => useRecentPhotos(url),
      { initialProps: { url: '/api/photos/space/space-1/recent' } }
    );
    await act(async () => { await result.current.loadMore(); });
    expect(result.current.photos).toEqual([{ id: 'a' }]);
    expect(result.current.hasLoaded).toBe(true);

    rerender({ url: '/api/photos/space/space-2/recent' });

    await waitFor(() => expect(result.current.photos).toEqual([]));
    expect(result.current.hasLoaded).toBe(false);
    expect(result.current.isLast).toBe(false);
  });

  it('marks hasLoaded true even when the fetch fails, without throwing', async () => {
    global.fetch = vi.fn(() => Promise.reject(new Error('network down')));
    vi.spyOn(console, 'error').mockImplementation(() => {});

    const { result } = renderHook(() => useRecentPhotos(BASE_URL));
    await act(async () => { await result.current.loadMore(); });

    expect(result.current.hasLoaded).toBe(true);
    expect(result.current.photos).toEqual([]);
  });
});
