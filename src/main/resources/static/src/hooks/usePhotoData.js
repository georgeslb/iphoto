import { useState, useEffect, useCallback } from 'react';

export function usePhotoData(effectivePath, isLightboxOpen) {
  const [photos, setPhotos] = useState([]);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(true);

  const fetchPhotos = useCallback(async (pageNum, reset = false, type = 'library', albumId = null, spaceId = null) => { // Added spaceId parameter
    try {
      let endpoint = '/api/photos';
      let sort = 'dateTaken,desc';

      if (type === 'garbage') {
        endpoint = '/api/photos/garbage';
        sort = 'garbageAt,desc';
      } else if (type === 'album' && albumId) {
        endpoint = `/api/albums/${albumId}/photos`;
      } else if (type === 'space' && spaceId) { // New condition for spaces
        endpoint = `/api/spaces/${spaceId}/photos`;
      }

      const res = await fetch(`${endpoint}?page=${pageNum}&size=50&sort=${sort}`);
      const data = await res.json();

      const content = Array.isArray(data) ? data : (data.content || []);
      const isLast = Array.isArray(data) ? true : (data.last === undefined ? true : data.last);

      setPhotos(prev => reset ? content : [...prev, ...content]);
      setHasMore(!isLast);
      if (reset) {
        setPage(0); // Reset page to 0 when a full refresh is performed
      }
    } catch (err) {
      console.error("Fetch photos failed:", err);
    }
  }, []);

  useEffect(() => {
    // Determine effective path for fetching data
    const currentSection = effectivePath.split('/')[1] || 'library';

    // Ignore direct navigation to an asset (handled by Lightbox and fallback logic)
    if (isLightboxOpen) return;

    // Reset photos and page when section changes (except for duplicates, which has its own management)
    if (!effectivePath.startsWith('/duplicates')) {
      setPhotos([]);
      setPage(0);
      setHasMore(true);
    }

    const albumMatch = effectivePath.match(/^\/albums\/(.+)$/);
    const spaceMatch = effectivePath.match(/^\/spaces\/(.+)$/); // New: Match for space paths

    if (effectivePath === '/') fetchPhotos(0, true, 'library');
    else if (effectivePath === '/garbage') fetchPhotos(0, true, 'garbage');
    else if (albumMatch) fetchPhotos(0, true, 'album', albumMatch[1]);
    else if (spaceMatch) fetchPhotos(0, true, 'space', null, spaceMatch[1]); // New: Handle space paths
    // Note: Duplicates and Map views are handled by their own components/hooks
  }, [effectivePath, fetchPhotos, isLightboxOpen]);

  useEffect(() => {
    if (page > 0) {
      const albumMatch = effectivePath.match(/^\/albums\/(.+)$/);
      const spaceMatch = effectivePath.match(/^\/spaces\/(.+)$/); // New: Match for space paths

      if (effectivePath === '/') fetchPhotos(page, false, 'library');
      else if (effectivePath === '/garbage') fetchPhotos(page, false, 'garbage');
      else if (albumMatch) fetchPhotos(page, false, 'album', albumMatch[1]);
      else if (spaceMatch) fetchPhotos(page, false, 'space', null, spaceMatch[1]); // New: Handle space paths
    }
  }, [page, fetchPhotos, effectivePath]);

  return { photos, setPhotos, page, setPage, hasMore, fetchPhotos };
}