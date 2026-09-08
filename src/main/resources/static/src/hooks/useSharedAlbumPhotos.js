import { useState, useEffect } from 'react';

// Paginated photo fetch for the guest/share flow, hitting the public share-scoped endpoint
// (AlbumShareController.getAlbumPhotos), keyed off an albumId rather than a route path. That
// endpoint returns a raw Spring Data Page<Photo> - since JsonConfiguration's
// @EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO), that's Spring Data's stable
// {content, page: {size, number, totalElements, totalPages}} DTO shape (no top-level `last` -
// same shape useDayIndexedPhotos.js's normalizePage already handles for the day-indexed
// endpoints), not the old raw-PageImpl {content, last, ...} shape.
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
        const isLast = data.page ? data.page.number >= data.page.totalPages - 1 : data.last !== false;
        setPhotos(prev => (page === 0 ? content : [...prev, ...content]));
        setHasMore(!isLast);
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
