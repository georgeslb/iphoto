import { useState, useEffect, useCallback } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';

export function useAppNavigationAndSelection(
  photos,
  currentDuplicateGroup,
  backgroundLocation,
  effectivePath,
  saveScrollPosition // This is now the function that reads from ref directly
) {
  const navigate = useNavigate();
  const location = useLocation();

  const [selectedPhoto, setSelectedPhoto] = useState(null);

  // Sync route and selectedPhoto
  useEffect(() => {
    const assetMatch = location.pathname.match(/^\/asset\/(.+)$/);
    const albumPhotoMatch = location.pathname.match(/^\/albums\/[^/]+\/asset\/(.+)$/);
    const photoIdMatch = assetMatch || albumPhotoMatch;

    if (!photoIdMatch) {
      setSelectedPhoto(null);
    } else {
        const photoId = photoIdMatch[1];
        if (!selectedPhoto && photos.length > 0) {
            // If we land on a direct URL and photos are loaded, set it
            const photo = photos.find(p => p.id === photoId);
            if (photo) setSelectedPhoto(photo);
        } else if (!selectedPhoto && currentDuplicateGroup) {
            // If we land on a direct URL inside duplicates
            const photo = currentDuplicateGroup.photos.find(p => p.id === photoId);
            if (photo) setSelectedPhoto(photo);
        } else if (!selectedPhoto) {
            // Hard refresh on an asset URL without a pre-loaded grid
            fetch(`/api/photos/${photoId}`)
                .then(res => res.ok ? res.json() : null)
                .then(data => {
                    if (data) setSelectedPhoto(data);
                });
        }
    }
  }, [location.pathname, photos, selectedPhoto, currentDuplicateGroup]);

  const handlePhotoClick = useCallback((photo) => {
    if (saveScrollPosition) {
      saveScrollPosition(); // Call without argument
    }
    setSelectedPhoto(photo);
    // Persist the FULL background location object (with its internal React Router key)
    // This prevents the background <Routes> from unmounting and remounting all images
    const bgLoc = backgroundLocation || location;
    
    let targetUrl;
    if (effectivePath.startsWith('/albums/')) {
      const albumId = effectivePath.split('/')[2];
      targetUrl = `/albums/${albumId}/asset/${photo.id}`;
    } else {
      targetUrl = `/asset/${photo.id}`;
    }
    
    navigate(targetUrl, { state: { backgroundLocation: bgLoc }, replace: true });
  }, [backgroundLocation, location, navigate, saveScrollPosition, effectivePath]);

  const handleCloseLightbox = useCallback(() => {
    setSelectedPhoto(null);
    // Determine the logical previous route cleanly
    if (backgroundLocation) {
      navigate(backgroundLocation.pathname + (backgroundLocation.search || '') + (backgroundLocation.hash || ''), { replace: true });
    } else {
      navigate('/', { replace: true }); // Fallback cleanly if there's no history
    }
  }, [backgroundLocation, navigate]);

  return {
    selectedPhoto,
    setSelectedPhoto,
    handlePhotoClick,
    handleCloseLightbox,
  };
}
