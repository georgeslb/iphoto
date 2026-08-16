import { useState, useEffect, useCallback } from 'react';
import { useLocale } from '../LocaleContext.jsx';

export function usePhotoData(effectivePath, isLightboxOpen) {
  const { messages } = useLocale();
  const [photos, setPhotos] = useState([]);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(true);

  const fetchPhotos = useCallback(async (pageNum, reset = false, type = 'library', albumId = null, spaceId = null) => {
    try {
      let endpoint = '/api/photos';
      let sort = 'dateTaken,desc';

      if (type === 'garbage') {
        endpoint = '/api/photos/garbage';
        sort = 'garbageAt,desc';
      } else if (type === 'album' && albumId) {
        endpoint = `/api/albums/${albumId}/photos`;
      } else if (type === 'space' && spaceId) {
        endpoint = `/api/spaces/${spaceId}/photos`;
      }

      const res = await fetch(`${endpoint}?page=${pageNum}&size=20&sort=${sort}`);
      const data = await res.json();

      const content = Array.isArray(data) ? data : (data.content || []);
      const isLast = Array.isArray(data) ? true : (data.last === undefined ? true : data.last);

      setPhotos(prev => (reset ? content : [...prev, ...content]));
      setHasMore(!isLast);
      if (reset) {
        setPage(0);
      }
    } catch (err) {
      console.error(messages["fetchPhotosFailed"], err);
    }
  }, [messages]);

  const loadPhotos = useCallback((page, reset) => {
    const albumMatch = effectivePath.match(/^\/albums\/(.+)$/);
    const spaceMatch = effectivePath.match(/^\/spaces\/(.+)$/);

    let type = 'library', albumId = null, spaceId = null;

    if (effectivePath === '/') {
        type = 'library';
    } else if (effectivePath === '/garbage') {
        type = 'garbage';
    } else if (albumMatch) {
        type = 'album';
        albumId = albumMatch[1];
    } else if (spaceMatch) {
        type = 'space';
        spaceId = spaceMatch[1];
    }

    fetchPhotos(page, reset, type, albumId, spaceId);
  }, [effectivePath, fetchPhotos]);

  useEffect(() => {
    if (isLightboxOpen) return;

    const isDuplicates = effectivePath.startsWith('/duplicates');
    loadPhotos(0, !isDuplicates);
  }, [effectivePath, loadPhotos, isLightboxOpen]);

  useEffect(() => {
    if (page > 0) {
      loadPhotos(page, false);
    }
  }, [page, loadPhotos]);

  return { photos, setPhotos, page, setPage, hasMore, fetchPhotos };
}