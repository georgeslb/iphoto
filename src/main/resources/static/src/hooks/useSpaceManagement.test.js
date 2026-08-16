import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useSpaceManagement } from './useSpaceManagement';
import { localeWrapper } from '../test/testUtils.jsx';

function jsonResponse(body, ok = true) {
  return Promise.resolve({ ok, json: () => Promise.resolve(body) });
}

function renderSpaceManagement(overrides = {}) {
  const setSelectedSpaceIds = overrides.setSelectedSpaceIds || vi.fn();
  const selectedMenu = overrides.selectedMenu ?? 'library';
  const { result } = renderHook(
    () => useSpaceManagement(selectedMenu, setSelectedSpaceIds),
    { wrapper: localeWrapper }
  );
  return { result, setSelectedSpaceIds };
}

describe('useSpaceManagement', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('creates a space, resets the form, and refreshes the list on success', async () => {
    global.fetch = vi.fn(() => jsonResponse([]));
    const { result } = renderSpaceManagement();

    act(() => {
      result.current.setNewSpaceName('Vacation 2024');
    });

    await act(async () => {
      await result.current.handleCreateSpace();
    });

    expect(global.fetch).toHaveBeenCalledWith(
      '/api/spaces',
      expect.objectContaining({ method: 'POST' })
    );
    expect(result.current.showCreateSpaceModal).toBe(false);
    expect(result.current.newSpaceName).toBe('');
  });

  it('surfaces the backend error message and does not reset the form on failure', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({ ok: false, json: () => Promise.resolve({ message: 'Name already taken' }) })
    );
    const { result } = renderSpaceManagement();

    act(() => {
      result.current.setNewSpaceName('Duplicate');
    });

    await act(async () => {
      await result.current.handleCreateSpace();
    });

    expect(result.current.spaceCreationError).toBe('Name already taken');
    expect(result.current.newSpaceName).toBe('Duplicate');
  });

  // Regression: handleDeleteSpaces used to fire all DELETE requests without checking
  // whether any of them actually succeeded, then unconditionally cleared selection and
  // refreshed — so a failed delete (e.g. trying to delete a protected space) silently
  // looked like it worked. Mirrors the equivalent, already-correct check in
  // useAlbumManagement's handleDeleteAlbums.
  it('does not clear selection or refresh when one of several space deletes fails', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    let call = 0;
    global.fetch = vi.fn(() => {
      call += 1;
      return jsonResponse({}, call !== 2); // second delete fails
    });
    const { result, setSelectedSpaceIds } = renderSpaceManagement();

    await act(async () => {
      await result.current.handleDeleteSpaces(['s1', 's2']);
    });

    expect(setSelectedSpaceIds).not.toHaveBeenCalled();
  });

  it('clears selection and refreshes when all space deletes succeed', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    global.fetch = vi.fn(() => jsonResponse({}));
    const { result, setSelectedSpaceIds } = renderSpaceManagement();

    await act(async () => {
      await result.current.handleDeleteSpaces(['s1', 's2']);
    });

    expect(setSelectedSpaceIds).toHaveBeenCalledWith([]);
  });

  it('does not delete anything if the user cancels the confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    global.fetch = vi.fn(() => jsonResponse({}));
    const { result, setSelectedSpaceIds } = renderSpaceManagement();

    await act(async () => {
      await result.current.handleDeleteSpaces(['s1']);
    });

    expect(global.fetch).not.toHaveBeenCalled();
    expect(setSelectedSpaceIds).not.toHaveBeenCalled();
  });

  it('fetches spaces when selectedMenu is "spaces"', async () => {
    global.fetch = vi.fn(() => jsonResponse([]));
    renderSpaceManagement({ selectedMenu: 'spaces' });

    expect(global.fetch).toHaveBeenCalledWith('/api/spaces');
  });

  it('fetches garbage spaces when selectedMenu is "garbage"', async () => {
    global.fetch = vi.fn(() => jsonResponse({ content: [] }));
    renderSpaceManagement({ selectedMenu: 'garbage' });

    expect(global.fetch).toHaveBeenCalledWith('/api/spaces/garbage');
  });

  it('does not fetch spaces or garbage spaces for an unrelated menu', () => {
    global.fetch = vi.fn(() => jsonResponse([]));
    renderSpaceManagement({ selectedMenu: 'library' });

    expect(global.fetch).not.toHaveBeenCalled();
  });
});
