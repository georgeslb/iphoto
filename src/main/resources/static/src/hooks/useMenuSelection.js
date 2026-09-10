import { useMemo } from 'react';
import { computeSelectionCapabilities } from '../utils/selectionCapabilities';

// Header/Lightbox action-button visibility. Was a POST /api/photos/selected round-trip on every
// selection/view change; now pure client-side derivation (the mutating endpoints enforce their
// own authorization, so these flags are cosmetic). Returns the same shape the endpoint did:
// { menuSelection: {...}, canBeDeleted, canBeMovedToMain, ... }.
export function useMenuSelection({
  pathname,
  currentSpaceId,
  currentAlbumId,
  isLightboxOpen,
  selectedAlbumIds = [],
  selectedSpaceIds = [],
  selectedIds = [],
  selectedPhoto = null,
  spaces = [],
}) {
  return useMemo(() => {
    // Same pathname -> menu mapping the old useMenuSelection fetch used.
    let menu = 'library';
    if (pathname === '/garbage') {
      menu = 'garbage';
    } else if (pathname.startsWith('/albums')) {
      menu = currentAlbumId ? 'album' : 'albums';
    } else if (pathname.startsWith('/spaces')) {
      menu = currentSpaceId ? 'space' : 'spaces';
    }
    if (isLightboxOpen) {
      menu = 'lightbox';
    }

    const currentSpace = menu === 'space'
      ? spaces.find(s => s.id === currentSpaceId)
      : null;
    // SpaceDTO.albumAllowed is true iff the space is the MAIN space (see SpaceServiceImpl).
    const currentSpaceIsMain = currentSpace ? !!currentSpace.albumAllowed : null;

    const albumTargeted =
      (menu === 'album' && !!currentAlbumId) ||
      (menu === 'albums' && selectedAlbumIds.length === 1);

    const selectedCount = selectedPhoto ? 1 : selectedIds.length;
    const singleSelectedInMainSpace =
      menu === 'lightbox' && selectedPhoto
        ? (selectedPhoto.inMainSpace ?? null)
        : null;

    // Does the current list's row selection contain something the matching bulk action can act on?
    // Drives the Header's "Delete Album(s)" / "Delete Selected Spaces" / garbage restore+purge
    // buttons (all rendered inside Header's `selectionMode` block, acting on the selected ids).
    const hasAlbumSelected = menu === 'albums' && selectedAlbumIds.length > 0;
    const mainSpaceIds = new Set(spaces.filter(s => s.albumAllowed).map(s => s.id));
    const hasNonMainSpaceSelected =
      menu === 'spaces' && selectedSpaceIds.some(id => !mainSpaceIds.has(id));
    // Trashed spaces on /garbage are never the MAIN space (it can't be sent to garbage).
    const hasGarbageSpaceSelected = menu === 'garbage' && selectedSpaceIds.length > 0;

    return computeSelectionCapabilities({
      menu,
      currentSpaceIsMain,
      albumTargeted,
      selectedCount,
      singleSelectedInMainSpace,
      hasAlbumSelected,
      hasNonMainSpaceSelected,
      hasGarbageSpaceSelected,
    });
  }, [pathname, currentSpaceId, currentAlbumId, isLightboxOpen, selectedAlbumIds, selectedSpaceIds, selectedIds, selectedPhoto, spaces]);
}
