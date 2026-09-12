import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { usePersonFaces } from './usePersonFaces';

describe('usePersonFaces', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('fetches page 0 for the given person on mount', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({ ok: true, json: () => Promise.resolve({ content: [{ id: 'f1' }], page: { number: 0, totalPages: 1 } }) })
    );

    const { result } = renderHook(() => usePersonFaces('person-1'));

    await waitFor(() => expect(result.current.faces).toHaveLength(1));
    expect(global.fetch.mock.calls[0][0]).toBe('/api/faces/person/person-1/faces?page=0&size=60');
    expect(result.current.hasMore).toBe(false);
    expect(result.current.loading).toBe(false);
  });

  it('does not fetch when personId is not yet known', () => {
    global.fetch = vi.fn();
    renderHook(() => usePersonFaces(null));
    expect(global.fetch).not.toHaveBeenCalled();
  });

  it('appends (not replaces) faces on loadMore and stops once isLast is true', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve({ content: [{ id: 'f1' }], page: { number: 0, totalPages: 2 } }) })
      .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve({ content: [{ id: 'f2' }], page: { number: 1, totalPages: 2 } }) });

    const { result } = renderHook(() => usePersonFaces('person-1'));
    await waitFor(() => expect(result.current.faces).toHaveLength(1));
    expect(result.current.hasMore).toBe(true);

    await act(async () => { result.current.loadMore(); await Promise.resolve(); });
    await waitFor(() => expect(result.current.faces).toHaveLength(2));
    expect(global.fetch.mock.calls[1][0]).toBe('/api/faces/person/person-1/faces?page=1&size=60');
    expect(result.current.faces.map(f => f.id)).toEqual(['f1', 'f2']);
    expect(result.current.hasMore).toBe(false);

    // A third call should be a no-op (hasMore already false) rather than an extra fetch.
    await act(async () => { result.current.loadMore(); });
    expect(global.fetch).toHaveBeenCalledTimes(2);
  });

  it('resets to page 0 and refetches when the person changes', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve({ content: [{ id: 'f1' }], page: { number: 0, totalPages: 1 } }) })
      .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve({ content: [{ id: 'f9' }], page: { number: 0, totalPages: 1 } }) });

    const { result, rerender } = renderHook(({ id }) => usePersonFaces(id), { initialProps: { id: 'person-1' } });
    await waitFor(() => expect(result.current.faces).toEqual([{ id: 'f1' }]));

    rerender({ id: 'person-2' });
    await waitFor(() => expect(result.current.faces).toEqual([{ id: 'f9' }]));
    expect(global.fetch.mock.calls[1][0]).toBe('/api/faces/person/person-2/faces?page=0&size=60');
  });

  it('unlinkFace removes the face from the local list on success without refetching', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve({ content: [{ id: 'f1' }, { id: 'f2' }], page: { number: 0, totalPages: 1 } }) })
      .mockResolvedValueOnce({ ok: true }); // DELETE /api/faces/f1/person

    const { result } = renderHook(() => usePersonFaces('person-1'));
    await waitFor(() => expect(result.current.faces).toHaveLength(2));

    await act(async () => { await result.current.unlinkFace('f1'); });

    expect(result.current.faces).toEqual([{ id: 'f2' }]);
    expect(global.fetch.mock.calls[1]).toEqual(['/api/faces/f1/person', { method: 'DELETE' }]);
    expect(global.fetch).toHaveBeenCalledTimes(2); // no extra refetch of the list
  });

  it('unlinkFace throws and leaves the list untouched when the request fails', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve({ content: [{ id: 'f1' }], page: { number: 0, totalPages: 1 } }) })
      .mockResolvedValueOnce({ ok: false, status: 404 });

    const { result } = renderHook(() => usePersonFaces('person-1'));
    await waitFor(() => expect(result.current.faces).toHaveLength(1));

    await expect(result.current.unlinkFace('f1')).rejects.toThrow();
    expect(result.current.faces).toEqual([{ id: 'f1' }]);
  });
});
