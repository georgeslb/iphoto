import { describe, it, expect } from 'vitest';
import { computeSelectionCapabilities } from './selectionCapabilities';

// Ports the old backend MenuServiceImpl.getSelection + PhotoServiceImpl.getSelection test intent
// - these were the cases the two backend integration tests (MenuServiceTest, PhotoServiceTest's
// selection assertions) covered, plus the branches only reachable by reading the Java.

describe('computeSelectionCapabilities - menu-level flags', () => {
  it('library/album/space menus allow upload; album also allows adding a share link', () => {
    expect(computeSelectionCapabilities({ menu: 'library' }).menuSelection.canUpload).toBe(true);
    expect(computeSelectionCapabilities({ menu: 'space' }).menuSelection.canUpload).toBe(true);
    const album = computeSelectionCapabilities({ menu: 'album' }).menuSelection;
    expect(album.canUpload).toBe(true);
    expect(album.canAddAAShareLinkToAlbum).toBe(true);
  });

  it('albums menu: can always create; can delete only when >=1 album is selected', () => {
    const noneSelected = computeSelectionCapabilities({ menu: 'albums', hasAlbumSelected: false }).menuSelection;
    expect(noneSelected.canCreateAlbum).toBe(true);
    expect(noneSelected.canDeleteAlbum).toBe(false);

    const oneSelected = computeSelectionCapabilities({ menu: 'albums', hasAlbumSelected: true }).menuSelection;
    expect(oneSelected.canCreateAlbum).toBe(true);
    expect(oneSelected.canDeleteAlbum).toBe(true);
    expect(oneSelected.canUpload).toBe(false);
  });

  it('spaces menu: can delete only when >=1 selected space is not the MAIN space', () => {
    expect(computeSelectionCapabilities({ menu: 'spaces', hasNonMainSpaceSelected: false }).menuSelection.canDeleteSpace).toBe(false);
    expect(computeSelectionCapabilities({ menu: 'spaces', hasNonMainSpaceSelected: true }).menuSelection.canDeleteSpace).toBe(true);
    // Selecting only the MAIN space leaves hasNonMainSpaceSelected false -> no delete button.
    expect(computeSelectionCapabilities({ menu: 'spaces', hasNonMainSpaceSelected: false }).menuSelection.canCreateSpace).toBe(true);
  });

  it('garbage menu allows restoring/permanently-deleting spaces only when >=1 is selected', () => {
    const m = computeSelectionCapabilities({ menu: 'garbage', hasGarbageSpaceSelected: true }).menuSelection;
    expect(m.canRestoreSpace).toBe(true);
    expect(m.canPermanentlyDeleteSpace).toBe(true);
    const none = computeSelectionCapabilities({ menu: 'garbage', hasGarbageSpaceSelected: false }).menuSelection;
    expect(none.canRestoreSpace).toBe(false);
    expect(none.canPermanentlyDeleteSpace).toBe(false);
  });
});

describe('computeSelectionCapabilities - photo flags', () => {
  it('an empty selection yields all-false photo flags regardless of menu', () => {
    const r = computeSelectionCapabilities({ menu: 'library', selectedCount: 0 });
    expect(r.canBeDeleted).toBe(false);
    expect(r.canEditDate).toBe(false);
    expect(r.canDownload).toBe(false);
    expect(r.canBeMovedToAlbum).toBe(false);
  });

  it('any non-empty selection can edit-date and download; can delete outside garbage', () => {
    const lib = computeSelectionCapabilities({ menu: 'library', selectedCount: 3 });
    expect(lib.canEditDate).toBe(true);
    expect(lib.canDownload).toBe(true);
    expect(lib.canBeDeleted).toBe(true);

    const gar = computeSelectionCapabilities({ menu: 'garbage', selectedCount: 3 });
    expect(gar.canEditDate).toBe(true);
    expect(gar.canDownload).toBe(true);
    expect(gar.canBeDeleted).toBe(false);
  });

  it('garbage selection can be restored and permanently deleted', () => {
    const gar = computeSelectionCapabilities({ menu: 'garbage', selectedCount: 1 });
    expect(gar.canBeRestored).toBe(true);
    expect(gar.canBePermanentlyDeleted).toBe(true);
  });

  it('library selection can be moved to an album (no space needed); a MAIN-space detail view can too', () => {
    expect(computeSelectionCapabilities({ menu: 'library', selectedCount: 2 }).canBeMovedToAlbum).toBe(true);
    expect(computeSelectionCapabilities({ menu: 'space', selectedCount: 2, currentSpaceIsMain: true }).canBeMovedToAlbum).toBe(true);
  });

  it('a non-MAIN space detail view can move its photos to Main but not straight to an album', () => {
    const r = computeSelectionCapabilities({ menu: 'space', selectedCount: 2, currentSpaceIsMain: false });
    expect(r.canBeMovedToMain).toBe(true);
    expect(r.canBeMovedToAlbum).toBe(false);
  });

  it('an album detail view: set-as-cover needs exactly one existing album target; remove-from-album is Lightbox-only (matches the old backend quirk - "album" menu never resolves a space so its move/remove block is skipped)', () => {
    const targeted = computeSelectionCapabilities({ menu: 'album', selectedCount: 1, albumTargeted: true });
    expect(targeted.canBeSetAsCover).toBe(true);
    expect(targeted.canBeRemovedFromAlbum).toBe(false); // only the Lightbox path sets this
    expect(targeted.canBeMovedToAlbum).toBe(false);

    const untargeted = computeSelectionCapabilities({ menu: 'albums', selectedCount: 1, albumTargeted: false });
    expect(untargeted.canBeSetAsCover).toBe(false);
  });

  it('lightbox: canBeModified always; canBeMovedToMain only when the one open photo is NOT in the MAIN space', () => {
    const inSpace = computeSelectionCapabilities({ menu: 'lightbox', selectedCount: 1, singleSelectedInMainSpace: false });
    expect(inSpace.canBeModified).toBe(true);
    expect(inSpace.canBeMovedToAlbum).toBe(true);
    expect(inSpace.canBeRemovedFromAlbum).toBe(true);
    expect(inSpace.canBeMovedToMain).toBe(true);

    const inMain = computeSelectionCapabilities({ menu: 'lightbox', selectedCount: 1, singleSelectedInMainSpace: true });
    expect(inMain.canBeMovedToMain).toBe(false);

    const unknown = computeSelectionCapabilities({ menu: 'lightbox', selectedCount: 1, singleSelectedInMainSpace: null });
    expect(unknown.canBeMovedToMain).toBe(false);
  });
});
