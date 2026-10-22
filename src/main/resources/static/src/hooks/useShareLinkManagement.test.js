import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useShareLinkManagement } from './useShareLinkManagement';
import { localeWrapper } from '../test/testUtils.jsx';

function jsonResponse(body, ok = true, status = 200) {
  return Promise.resolve({ ok, status, json: () => Promise.resolve(body) });
}

function renderShareLinkManagement() {
  return renderHook(() => useShareLinkManagement(), { wrapper: localeWrapper });
}

async function fillValidForm(result) {
  act(() => {
    result.current.setNewShareLinkName('my-vacation-album');
    result.current.setAlbumIdForShareLink('album-1');
    result.current.setNewShareLinkExpiresInDays(7);
  });
}

describe('useShareLinkManagement', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('rejects an empty link name without calling the backend', async () => {
    global.fetch = vi.fn();
    const { result } = renderShareLinkManagement();

    act(() => {
      result.current.setAlbumIdForShareLink('album-1');
    });

    await act(async () => {
      await result.current.handleCreateShareLink();
    });

    expect(global.fetch).not.toHaveBeenCalled();
    expect(result.current.shareLinkCreationError).toBeTruthy();
  });

  it('rejects a link name with characters that are not URL-friendly', async () => {
    global.fetch = vi.fn();
    const { result } = renderShareLinkManagement();

    act(() => {
      result.current.setNewShareLinkName('not a valid name!');
      result.current.setAlbumIdForShareLink('album-1');
    });

    await act(async () => {
      await result.current.handleCreateShareLink();
    });

    expect(global.fetch).not.toHaveBeenCalled();
    expect(result.current.shareLinkCreationError).toBeTruthy();
  });

  it('rejects a non-positive expiration', async () => {
    global.fetch = vi.fn();
    const { result } = renderShareLinkManagement();

    act(() => {
      result.current.setNewShareLinkName('valid-name');
      result.current.setAlbumIdForShareLink('album-1');
      result.current.setNewShareLinkExpiresInDays(0);
    });

    await act(async () => {
      await result.current.handleCreateShareLink();
    });

    expect(global.fetch).not.toHaveBeenCalled();
    expect(result.current.shareLinkCreationError).toBeTruthy();
  });

  it('rejects creating a link with no album selected', async () => {
    global.fetch = vi.fn();
    const { result } = renderShareLinkManagement();

    act(() => {
      result.current.setNewShareLinkName('valid-name');
    });

    await act(async () => {
      await result.current.handleCreateShareLink();
    });

    expect(global.fetch).not.toHaveBeenCalled();
    expect(result.current.shareLinkCreationError).toBeTruthy();
  });

  it('creates the link and resets the form on success', async () => {
    const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
    global.fetch = vi.fn(() => jsonResponse({}));
    const { result } = renderShareLinkManagement();
    await fillValidForm(result);

    await act(async () => {
      await result.current.handleCreateShareLink();
    });

    expect(global.fetch).toHaveBeenCalledWith(
      '/api/share',
      expect.objectContaining({ method: 'POST' })
    );
    expect(result.current.newShareLinkName).toBe('');
    expect(result.current.albumIdForShareLink).toBe(null);
    expect(result.current.showCreateShareLinkModal).toBe(false);
    // Regression: the success alert used to interpolate the raw album UUID
    // (`Share link "<uuid>"/"name" created successfully!`) instead of just the link
    // name — meaningless and confusing to a user. Assert the id never leaks into it.
    expect(alertSpy).toHaveBeenCalledWith(expect.not.stringContaining('album-1'));
    expect(alertSpy).toHaveBeenCalledWith(expect.stringContaining('my-vacation-album'));
  });

  it('surfaces the backend error message on failure and keeps the form filled in', async () => {
    global.fetch = vi.fn(() =>
      jsonResponse({ message: 'That name is already taken' }, false, 409)
    );
    const { result } = renderShareLinkManagement();
    await fillValidForm(result);

    await act(async () => {
      await result.current.handleCreateShareLink();
    });

    expect(result.current.shareLinkCreationError).toBe('That name is already taken');
    expect(result.current.newShareLinkName).toBe('my-vacation-album');
  });

  it('handles empty or non-JSON error response gracefully without crashing', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve({
        ok: false,
        status: 409,
        json: () => Promise.reject(new SyntaxError('Unexpected end of JSON input'))
      })
    );
    const { result } = renderShareLinkManagement();
    await fillValidForm(result);

    await act(async () => {
      await result.current.handleCreateShareLink();
    });

    expect(result.current.shareLinkCreationError).toContain('409');
  });
});
