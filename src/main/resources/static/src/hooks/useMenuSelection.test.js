import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useMenuSelection } from './useMenuSelection';

describe('useMenuSelection', () => {
  beforeEach(() => {
    global.fetch = vi.fn(() =>
      Promise.resolve({
        json: () => Promise.resolve({ menuSelection: {} }),
      })
    );
  });

  // Regression: /api/photos/selected was always sent the grid multi-selection
  // (selectedIds), even while viewing a single photo in the Lightbox with nothing
  // checkbox-selected — so the backend had no way to know which photo was open and
  // returned wrong capability flags for the Lightbox's own action buttons.
  it('sends the viewed photo id when a single photo is selected (Lightbox open)', async () => {
    const setPhotoSelection = vi.fn();
    const selectedPhoto = { id: 'photo-123' };

    renderHook(() =>
      useMenuSelection('/', null, null, true, [], [], [], selectedPhoto, setPhotoSelection)
    );

    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(1));

    const [, options] = global.fetch.mock.calls[0];
    const body = JSON.parse(options.body);
    expect(body.photosId).toEqual(['photo-123']);
  });

  it('falls back to the grid multi-selection when no single photo is being viewed', async () => {
    const setPhotoSelection = vi.fn();

    renderHook(() =>
      useMenuSelection('/', null, null, false, [], [], ['a', 'b'], null, setPhotoSelection)
    );

    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(1));

    const [, options] = global.fetch.mock.calls[0];
    const body = JSON.parse(options.body);
    expect(body.photosId).toEqual(['a', 'b']);
  });

  it('prefers the viewed photo over a stale grid selection', async () => {
    const setPhotoSelection = vi.fn();
    const selectedPhoto = { id: 'photo-999' };

    renderHook(() =>
      useMenuSelection('/', null, null, true, [], [], ['stale-1', 'stale-2'], selectedPhoto, setPhotoSelection)
    );

    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(1));

    const [, options] = global.fetch.mock.calls[0];
    const body = JSON.parse(options.body);
    expect(body.photosId).toEqual(['photo-999']);
  });

  it('derives the correct menu for an album detail page', async () => {
    const setPhotoSelection = vi.fn();

    renderHook(() =>
      useMenuSelection('/albums/abc', null, 'abc', false, [], [], [], null, setPhotoSelection)
    );

    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(1));

    const [, options] = global.fetch.mock.calls[0];
    const body = JSON.parse(options.body);
    expect(body.menuSelectionRequest.menu).toBe('album');
    expect(body.menuSelectionRequest.albumIds).toEqual(['abc']);
  });

  // Regression: the request body used to send `spaceId`/`albumId` (singular), but the
  // backend's MenuSelectionRequest record fields are `spaceIds`/`albumIds` (plural) — a
  // silent key mismatch that left them null server-side (Jackson doesn't fail on this by
  // default), permanently breaking canBeSetAsCover/canBeRemovedFromAlbum/canBeMovedToAlbum/
  // canBeMovedToMain. Confirmed live against the real backend before fixing.
  it('sends spaceIds/albumIds (plural, matching the backend record fields) not spaceId/albumId', async () => {
    const setPhotoSelection = vi.fn();

    renderHook(() =>
      useMenuSelection('/albums/abc', null, 'abc', false, [], [], [], null, setPhotoSelection)
    );

    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(1));

    const [, options] = global.fetch.mock.calls[0];
    const body = JSON.parse(options.body);
    expect(body.menuSelectionRequest).not.toHaveProperty('albumId');
    expect(body.menuSelectionRequest).not.toHaveProperty('spaceId');
    expect(body.menuSelectionRequest.albumIds).toEqual(['abc']);
    expect(body.menuSelectionRequest.spaceIds).toEqual([]);
  });

  // Simplification: the backend now infers "Lightbox is open" purely from menu === 'lightbox'
  // instead of a separate boolean flag, so the request should carry no `lightbox` property at all.
  it('sends menu "lightbox" (and no separate lightbox property) when the Lightbox is open, overriding the underlying page menu', async () => {
    const setPhotoSelection = vi.fn();
    const selectedPhoto = { id: 'photo-123' };

    renderHook(() =>
      useMenuSelection('/albums/abc', null, 'abc', true, [], [], [], selectedPhoto, setPhotoSelection)
    );

    await waitFor(() => expect(global.fetch).toHaveBeenCalledTimes(1));

    const [, options] = global.fetch.mock.calls[0];
    const body = JSON.parse(options.body);
    expect(body.menuSelectionRequest.menu).toBe('lightbox');
    expect(body.menuSelectionRequest).not.toHaveProperty('lightbox');
  });
});
