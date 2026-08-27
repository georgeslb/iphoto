import { useState, useEffect } from 'react';

// Paginated photo fetch for the guest/share flow. Mirrors the Page<Photo> parsing usePhotoData.js
// already relies on ({ content, last }), but hits the public share-scoped endpoint instead of
// the authenticated one, and is keyed off an albumId rather than a route path.
export function useSharedAlbumPhotos(albumId) {
  const [photos, setPhotos] = useState([]);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);

  useEffect(() => {
    setPhotos([]);
    setPage(0);
    setHasMore(false);
  }, [albumId]);

  useEffect(() => {
    if (!albumId) return;
    let cancelled = false;

    fetch(`/api/albums/share/${albumId}/photos?page=${page}&size=50`)
      .then(res => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
      })
      .then(data => {
        if (cancelled) return;
        const content = data.content || [];
        setPhotos(prev => (page === 0 ? content : [...prev, ...content]));
        setHasMore(data.last === false);
      })
      .catch(err => {
        if (!cancelled) {
          console.error('Error fetching shared album photos:', err);
          setHasMore(false);
        }
      });

    return () => { cancelled = true; };
  }, [albumId, page]);

  return { photos, hasMore, setPage };
}
