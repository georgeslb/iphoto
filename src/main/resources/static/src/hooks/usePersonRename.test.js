import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { usePersonRename } from './usePersonRename';

describe('usePersonRename', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('applies a plain rename directly when there is no name collision', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ id: 'p1', name: 'Bob', photoCount: 3 }) })
    );
    const onRenamed = vi.fn();
    const { result } = renderHook(() => usePersonRename(onRenamed));

    await act(async () => { await result.current.rename('p1', 'Bob'); });

    expect(global.fetch).toHaveBeenCalledWith('/api/faces/person/p1', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({ name: 'Bob', confirmed: false, mergeWithPersonIds: [] }),
    }));
    expect(onRenamed).toHaveBeenCalledWith({ id: 'p1', name: 'Bob', photoCount: 3 });
    expect(result.current.collisionCandidates).toBeNull();
  });

  // A 409 surfaces the colliding people instead of applying anything — the caller (Persons.jsx /
  // PersonDetail.jsx) renders PersonMergePrompt from `collisionCandidates`.
  it('surfaces collision candidates on 409 without calling onRenamed', async () => {
    const candidates = [{ id: 'p2', name: 'Bob', photoCount: 5 }, { id: 'p3', name: 'Bob', photoCount: 1 }];
    global.fetch = vi.fn(() =>
      Promise.resolve({ ok: false, status: 409, json: () => Promise.resolve(candidates) })
    );
    const onRenamed = vi.fn();
    const { result } = renderHook(() => usePersonRename(onRenamed));

    await act(async () => { await result.current.rename('p1', 'Bob'); });

    expect(result.current.collisionCandidates).toEqual(candidates);
    expect(result.current.pendingName).toBe('Bob');
    expect(onRenamed).not.toHaveBeenCalled();
  });

  it('confirmRename sends confirmed:true with the selected merge ids and calls onRenamed', async () => {
    const candidates = [{ id: 'p2', name: 'Bob', photoCount: 5 }, { id: 'p3', name: 'Bob', photoCount: 1 }];
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ ok: false, status: 409, json: () => Promise.resolve(candidates) })
      .mockResolvedValueOnce({ ok: true, status: 200, json: () => Promise.resolve({ id: 'p1', name: 'Bob', photoCount: 9 }) });
    const onRenamed = vi.fn();
    const { result } = renderHook(() => usePersonRename(onRenamed));

    await act(async () => { await result.current.rename('p1', 'Bob'); });
    await waitFor(() => expect(result.current.collisionCandidates).not.toBeNull());

    act(() => { result.current.toggleMergeCandidate('p2'); });
    expect(result.current.selectedMergeIds).toEqual(['p2']);

    await act(async () => { await result.current.confirmRename(); });

    expect(global.fetch.mock.calls[1]).toEqual(['/api/faces/person/p1', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({ name: 'Bob', confirmed: true, mergeWithPersonIds: ['p2'] }),
    })]);
    expect(onRenamed).toHaveBeenCalledWith({ id: 'p1', name: 'Bob', photoCount: 9 });
    expect(result.current.collisionCandidates).toBeNull();
  });

  // Confirming with nothing selected is a deliberate "rename anyway, don't merge with any of
  // these" choice — not blocked, and not the same as cancelRename (which doesn't rename at all).
  it('confirmRename with zero selected candidates still renames, just without merging', async () => {
    const candidates = [{ id: 'p2', name: 'Bob', photoCount: 5 }];
    global.fetch = vi.fn()
      .mockResolvedValueOnce({ ok: false, status: 409, json: () => Promise.resolve(candidates) })
      .mockResolvedValueOnce({ ok: true, status: 200, json: () => Promise.resolve({ id: 'p1', name: 'Bob', photoCount: 3 }) });
    const onRenamed = vi.fn();
    const { result } = renderHook(() => usePersonRename(onRenamed));

    await act(async () => { await result.current.rename('p1', 'Bob'); });
    await waitFor(() => expect(result.current.collisionCandidates).not.toBeNull());

    await act(async () => { await result.current.confirmRename(); });

    expect(global.fetch.mock.calls[1][1].body).toBe(JSON.stringify({ name: 'Bob', confirmed: true, mergeWithPersonIds: [] }));
    expect(onRenamed).toHaveBeenCalled();
  });

  it('cancelRename clears the prompt without applying anything', async () => {
    const candidates = [{ id: 'p2', name: 'Bob', photoCount: 5 }];
    global.fetch = vi.fn(() =>
      Promise.resolve({ ok: false, status: 409, json: () => Promise.resolve(candidates) })
    );
    const onRenamed = vi.fn();
    const { result } = renderHook(() => usePersonRename(onRenamed));

    await act(async () => { await result.current.rename('p1', 'Bob'); });
    await waitFor(() => expect(result.current.collisionCandidates).not.toBeNull());

    act(() => { result.current.cancelRename(); });

    expect(result.current.collisionCandidates).toBeNull();
    expect(onRenamed).not.toHaveBeenCalled();
  });
});
