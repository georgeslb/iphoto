import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useAlbumManagement } from './useAlbumManagement';
import { localeWrapper } from '../test/testUtils.jsx';

function jsonResponse(body, ok = true) {
  return Promise.resolve({ ok, json: () => Promise.resolve(body) });
}

function renderAlbumManagement(overrides = {}) {
  const setSelectedIds = overrides.setSelectedIds || vi.fn();
  // Stabilize selectedIds outside the render callback: useAlbumManagement's effects
  // depend on it by reference, so a fresh array literal created inside the render
  // callback would change identity every render and loop forever.
  const effectivePath = overrides.effectivePath ?? '/albums';
  const isLightboxOpen = overrides.isLightboxOpen ?? false;
  const selectedIds = overrides.selectedIds ?? ['photo-1'];
  const refreshAndMaintainScroll = overrides.refreshAndMaintainScroll;
  const { result } = renderHook(
    () => useAlbumManagement(effectivePath, isLightboxOpen, selectedIds, setSelectedIds, refreshAndMaintainScroll),
    { wrapper: localeWrapper }
  );
  return { result, setSelectedIds };
}

describe('useAlbumManagement', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  // Regression: handleAddPhotosToAlbums used to list fetchAlbums as a useCallback
  // dependency but never actually called it — so after successfully adding photos to
  // an album, the local `albums` state (and its photoCount) went stale until an
  // unrelated navigation happened to refetch it.
  it('refreshes the album list after successfully adding photos to albums', async () => {
    global.fetch = vi.fn((url, options) => {
      if (options?.method === 'POST') return jsonResponse({});
      return jsonResponse([]); // fetchAlbums GET
    });

    const { result } = renderAlbumManagement();

    act(() => {
      result.current.setTargetAlbumIds(['album-1']);
    });

    const getCallsBefore = global.fetch.mock.calls.filter(([, o]) => !o || o.method === undefined).length;

    await act(async () => {
      await result.current.handleAddPhotosToAlbums();
    });

    const getCallsAfter = global.fetch.mock.calls.filter(([, o]) => !o || o.method === undefined).length;
    expect(getCallsAfter).toBeGreaterThan(getCallsBefore);
  });

  // Regression: same bug as above, for handleSetCoverPhoto.
  it('refreshes the album list after successfully setting a cover photo', async () => {
    global.fetch = vi.fn((url, options) => {
      if (options?.method === 'PUT') return jsonResponse({});
      return jsonResponse([]); // fetchAlbums GET
    });

    const { result } = renderAlbumManagement();
    const getCallsBefore = global.fetch.mock.calls.length;

    await act(async () => {
      await result.current.handleSetCoverPhoto('album-1', 'photo-1');
    });

    const getRequests = global.fetch.mock.calls.filter(([, o]) => !o || o.method === undefined);
    expect(getRequests.length).toBeGreaterThan(0);
    expect(global.fetch.mock.calls.length).toBeGreaterThan(getCallsBefore);
  });

  it('does not create an album with an empty name', async () => {
    global.fetch = vi.fn(() => jsonResponse({}));
    // effectivePath deliberately not '/albums': that path triggers an unrelated
    // mount-time fetchAlbums() call which would confound the "fetch not called" check.
    const { result } = renderAlbumManagement({ effectivePath: '/' });

    await act(async () => {
      await result.current.handleCreateAlbum();
    });

    expect(global.fetch).not.toHaveBeenCalled();
    expect(result.current.albumCreationError).toBeTruthy();
  });

  it('creates an album and refreshes the list on success', async () => {
    global.fetch = vi.fn(() => jsonResponse([]));
    const { result } = renderAlbumManagement();

    act(() => {
      result.current.setNewAlbumName('Vacation');
    });

    await act(async () => {
      await result.current.handleCreateAlbum();
    });

    expect(global.fetch).toHaveBeenCalledWith(
      '/api/albums',
      expect.objectContaining({ method: 'POST' })
    );
    expect(result.current.showCreateAlbumModal).toBe(false);
  });

  // Deleting albums should not clear selection / report success if any delete failed
  // (mirrors the handleDeleteSpaces fix in useSpaceManagement).
  it('does not clear selection when one of several album deletes fails', async () => {
    let call = 0;
    global.fetch = vi.fn(() => {
      call += 1;
      return jsonResponse({}, call !== 2); // second delete fails
    });
    const { result, setSelectedIds } = renderAlbumManagement();

    await act(async () => {
      await result.current.handleDeleteAlbums(['a1', 'a2']);
    });

    expect(setSelectedIds).not.toHaveBeenCalled();
  });

  it('clears selection and refreshes when all album deletes succeed', async () => {
    global.fetch = vi.fn(() => jsonResponse({}));
    const { result, setSelectedIds } = renderAlbumManagement();

    await act(async () => {
      await result.current.handleDeleteAlbums(['a1', 'a2']);
    });

    expect(setSelectedIds).toHaveBeenCalledWith([]);
  });

  it('falls back to the current grid selection when removing photos from an album with no explicit ids', async () => {
    global.fetch = vi.fn(() => jsonResponse({}));
    const { result, setSelectedIds } = renderAlbumManagement({ selectedIds: ['photo-1', 'photo-2'] });

    await act(async () => {
      await result.current.handleRemovePhotosFromAlbum('album-1');
    });

    const deleteCall = global.fetch.mock.calls.find(([, o]) => o?.method === 'DELETE');
    expect(JSON.parse(deleteCall[1].body).photoIds).toEqual(['photo-1', 'photo-2']);
    expect(setSelectedIds).toHaveBeenCalledWith([]);
  });
});
