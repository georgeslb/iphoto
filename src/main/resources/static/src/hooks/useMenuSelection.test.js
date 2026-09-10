import { describe, it, expect } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useMenuSelection } from './useMenuSelection';

// useMenuSelection is now a pure derivation (no fetch) - it maps the current route + selection
// onto the same capability-flag object POST /api/photos/selected used to return. The exhaustive
// rule matrix lives in selectionCapabilities.test.js; this file just checks the hook wires the
// route/selection inputs through correctly. Argument objects are hoisted out of the renderHook
// callback per CLAUDE.md (a fresh literal every render never stabilises the useMemo deps).

const NONE = [];

describe('useMenuSelection', () => {
  it('derives the "album" menu (and its menu-level flags) for an album detail route', () => {
    const args = {
      pathname: '/albums/abc', currentSpaceId: null, currentAlbumId: 'abc', isLightboxOpen: false,
      selectedAlbumIds: NONE, selectedSpaceIds: NONE, selectedIds: NONE, selectedPhoto: null, spaces: NONE,
    };
    const { result } = renderHook(() => useMenuSelection(args));
    expect(result.current.menuSelection.canUpload).toBe(true);
    expect(result.current.menuSelection.canAddAAShareLinkToAlbum).toBe(true);
  });

  it('an open Lightbox overrides the underlying page menu', () => {
    const selectedPhoto = { id: 'photo-123', inMainSpace: true };
    const args = {
      pathname: '/albums/abc', currentSpaceId: null, currentAlbumId: 'abc', isLightboxOpen: true,
      selectedAlbumIds: NONE, selectedSpaceIds: NONE, selectedIds: NONE, selectedPhoto, spaces: NONE,
    };
    const { result } = renderHook(() => useMenuSelection(args));
    // canBeModified is only ever set by the 'lightbox' branch.
    expect(result.current.canBeModified).toBe(true);
  });

  it('treats the open Lightbox photo as the selection when nothing is checkbox-selected', () => {
    const selectedPhoto = { id: 'photo-999', inMainSpace: true };
    const args = {
      pathname: '/', currentSpaceId: null, currentAlbumId: null, isLightboxOpen: true,
      selectedAlbumIds: NONE, selectedSpaceIds: NONE, selectedIds: NONE, selectedPhoto, spaces: NONE,
    };
    const { result } = renderHook(() => useMenuSelection(args));
    expect(result.current.canDownload).toBe(true); // true for any non-empty selection
  });

  it('uses the grid multi-selection when no photo is open in the Lightbox', () => {
    const args = {
      pathname: '/', currentSpaceId: null, currentAlbumId: null, isLightboxOpen: false,
      selectedAlbumIds: NONE, selectedSpaceIds: NONE, selectedIds: ['a', 'b'], selectedPhoto: null, spaces: NONE,
    };
    const { result } = renderHook(() => useMenuSelection(args));
    expect(result.current.canDownload).toBe(true);
    expect(result.current.canBeDeleted).toBe(true); // library menu, not garbage
  });

  it('drives "Move to Main" from the open photo\'s inMainSpace flag', () => {
    const base = {
      pathname: '/', currentSpaceId: null, currentAlbumId: null, isLightboxOpen: true,
      selectedAlbumIds: NONE, selectedSpaceIds: NONE, selectedIds: NONE, spaces: NONE,
    };
    const inSpace = renderHook(() => useMenuSelection({ ...base, selectedPhoto: { id: 'p', inMainSpace: false } }));
    expect(inSpace.result.current.canBeMovedToMain).toBe(true);

    const inMain = renderHook(() => useMenuSelection({ ...base, selectedPhoto: { id: 'p', inMainSpace: true } }));
    expect(inMain.result.current.canBeMovedToMain).toBe(false);
  });

  it('resolves the current space\'s MAIN-or-not from the spaces list on a space detail route', () => {
    const spaces = [{ id: 'sp-1', albumAllowed: false }]; // not MAIN
    const args = {
      pathname: '/spaces/sp-1', currentSpaceId: 'sp-1', currentAlbumId: null, isLightboxOpen: false,
      selectedAlbumIds: NONE, selectedSpaceIds: NONE, selectedIds: ['x'], selectedPhoto: null, spaces,
    };
    const { result } = renderHook(() => useMenuSelection(args));
    expect(result.current.canBeMovedToMain).toBe(true); // non-MAIN space -> can move its photos to Main
    expect(result.current.canBeMovedToAlbum).toBe(false); // ... but not straight to an album
  });

  it('shows "Delete Album(s)" only once an album row is selected on the /albums list', () => {
    const base = {
      pathname: '/albums', currentSpaceId: null, currentAlbumId: null, isLightboxOpen: false,
      selectedSpaceIds: NONE, selectedIds: NONE, selectedPhoto: null, spaces: NONE,
    };
    expect(renderHook(() => useMenuSelection({ ...base, selectedAlbumIds: NONE }))
      .result.current.menuSelection.canDeleteAlbum).toBe(false);
    expect(renderHook(() => useMenuSelection({ ...base, selectedAlbumIds: ['al-1'] }))
      .result.current.menuSelection.canDeleteAlbum).toBe(true);
  });

  it('shows "Delete Selected Spaces" for a non-MAIN selection, never for the MAIN space', () => {
    const spaces = [{ id: 'main-1', albumAllowed: true }, { id: 'sp-2', albumAllowed: false }];
    const base = {
      pathname: '/spaces', currentSpaceId: null, currentAlbumId: null, isLightboxOpen: false,
      selectedAlbumIds: NONE, selectedIds: NONE, selectedPhoto: null, spaces,
    };
    expect(renderHook(() => useMenuSelection({ ...base, selectedSpaceIds: NONE }))
      .result.current.menuSelection.canDeleteSpace).toBe(false);
    expect(renderHook(() => useMenuSelection({ ...base, selectedSpaceIds: ['main-1'] }))
      .result.current.menuSelection.canDeleteSpace).toBe(false); // MAIN space can't be removed
    expect(renderHook(() => useMenuSelection({ ...base, selectedSpaceIds: ['sp-2'] }))
      .result.current.menuSelection.canDeleteSpace).toBe(true);
    expect(renderHook(() => useMenuSelection({ ...base, selectedSpaceIds: ['main-1', 'sp-2'] }))
      .result.current.menuSelection.canDeleteSpace).toBe(true); // has a removable one
  });

  it('shows garbage space restore/purge only once a trashed space is selected', () => {
    const base = {
      pathname: '/garbage', currentSpaceId: null, currentAlbumId: null, isLightboxOpen: false,
      selectedAlbumIds: NONE, selectedIds: NONE, selectedPhoto: null, spaces: NONE,
    };
    const none = renderHook(() => useMenuSelection({ ...base, selectedSpaceIds: NONE })).result.current.menuSelection;
    expect(none.canRestoreSpace).toBe(false);
    expect(none.canPermanentlyDeleteSpace).toBe(false);
    const one = renderHook(() => useMenuSelection({ ...base, selectedSpaceIds: ['gs-1'] })).result.current.menuSelection;
    expect(one.canRestoreSpace).toBe(true);
    expect(one.canPermanentlyDeleteSpace).toBe(true);
  });

  it('returns all-false photo flags (menu flags still computed) when nothing is selected', () => {
    const args = {
      pathname: '/', currentSpaceId: null, currentAlbumId: null, isLightboxOpen: false,
      selectedAlbumIds: NONE, selectedSpaceIds: NONE, selectedIds: NONE, selectedPhoto: null, spaces: NONE,
    };
    const { result } = renderHook(() => useMenuSelection(args));
    expect(result.current.canBeDeleted).toBe(false);
    expect(result.current.canDownload).toBe(false);
    expect(result.current.menuSelection.canUpload).toBe(true); // library menu still allows upload
  });
});
