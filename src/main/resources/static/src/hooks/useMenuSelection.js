import { useEffect, useCallback } from 'react';

export function useMenuSelection(pathname, spaceId, albumId, isLightboxOpen, selectedAlbumIds, selectedSpaceIds, selectedIds, selectedPhoto
                                 , setPhotoSelection) {
  const fetchSelection = useCallback(async () => {

    let menu = 'library';
    let reqAlbumIds = [];
    let reqSpaceIds = [];

    if (pathname === '/garbage') {
      menu = 'garbage';
      reqSpaceIds = selectedSpaceIds;
    } else if (pathname.startsWith('/albums')) {
      if (albumId) {
        menu = 'album';
        reqAlbumIds = [albumId];
      } else {
        menu = 'albums';
        reqAlbumIds = selectedAlbumIds;
      }
    } else if (pathname.startsWith('/spaces')) {
      if (spaceId) {
        menu = 'space';
        reqSpaceIds = [spaceId];
      } else {
        menu = 'spaces';
        reqSpaceIds = selectedSpaceIds;
      }
    }

    if (isLightboxOpen) {
      menu = 'lightbox';
    }

    const menuSelectionRequest = {
      menu: menu,
      spaceIds: reqSpaceIds || [],
      albumIds: reqAlbumIds || [],
    };

    const request = {
      menuSelectionRequest: menuSelectionRequest,
      photosId: selectedPhoto ? [selectedPhoto.id] : selectedIds,
    };

    try {
      const res = await fetch('/api/photos/selected', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
      });
      const data = await res.json();
      setPhotoSelection(data);
    } catch (error) {
      console.error('Error fetching menu selection:', error);
    }
  }, [pathname, spaceId, albumId, isLightboxOpen, selectedAlbumIds, selectedSpaceIds, selectedIds, selectedPhoto, setPhotoSelection]);

  useEffect(() => {
    fetchSelection();
  }, [fetchSelection]);
}