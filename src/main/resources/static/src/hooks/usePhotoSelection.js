import { useState, useEffect, useCallback } from 'react';

export function usePhotoSelection(selectedIds, pathname, spaceId, albumId) {
  const [selection, setSelection] = useState({
    canBeDeleted: false,
    canBePermanentlyDeleted: false,
    canBeMovedToMain: false,
    canBeMovedToAlbum: false,
    canBeRemovedFromAlbum: false,
    canBeModified: false,
    canBeSetAsCover: false,
    canBeRestored: false,
  });

  const fetchSelection = useCallback(async () => {
    if (selectedIds.length === 0) {
      setSelection({
        canBeDeleted: false,
        canBePermanentlyDeleted: false,
        canBeMovedToMain: false,
        canBeMovedToAlbum: false,
        canBeRemovedFromAlbum: false,
        canBeModified: false,
        canBeSetAsCover: false,
        canBeRestored: false,
      });
      return;
    }

    let menu = 'library';
    if (pathname === '/garbage') {
      menu = 'garbage';
    } else if (pathname.startsWith('/albums')) {
      menu = 'album';
    } else if (pathname.startsWith('/spaces')) {
      menu = 'space';
    }

    const request = {
      photosId: selectedIds,
      menu: menu,
      spaceIds: spaceId,
      albumId: albumId,
    };

    try {
      const res = await fetch('/api/photos/selected', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      });
      const data = await res.json();
      setSelection(data);
    } catch (error) {
      console.error('Error fetching photo selection:', error);
    }
  }, [selectedIds, pathname, spaceId, albumId]);

  useEffect(() => {
    fetchSelection();
  }, [fetchSelection]);

  return selection;
}