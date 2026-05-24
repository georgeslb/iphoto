import { useCallback } from 'react';

export function useRefreshLogic(
  mainContentRef,
  page,
  effectivePath,
  fetchAlbums,
  duplicatesPage,
  fetchDuplicates,
  setPhotos
) {
  const refreshAndMaintainScroll = useCallback(async () => {
    const scrollPosition = mainContentRef.current?.scrollTop;
    const currentPage = page;

    if (effectivePath.startsWith('/duplicates')) {
      await fetchDuplicates(duplicatesPage);
      return;
    }

    const type = effectivePath === '/garbage' ? 'garbage' : (effectivePath.startsWith('/albums/') ? 'album' : 'library');
    const albumId = type === 'album' ? effectivePath.split('/').pop() : null;

    const fetchPage = async (pageNum) => {
      let endpoint = '/api/photos';
      let sort = 'dateTaken,desc';

      if (type === 'garbage') {
        endpoint = '/api/photos/garbage';
        sort = 'garbageAt,desc';
      } else if (type === 'album' && albumId) {
        endpoint = `/api/albums/${albumId}/photos`;
      }

      const res = await fetch(`${endpoint}?page=${pageNum}&size=50&sort=${sort}`);
      if (!res.ok) return [];
      const data = await res.json();
      return Array.isArray(data) ? data : (data.content || []);
    };

    const allPagesPromises = [];
    for (let i = 0; i <= currentPage; i++) {
      allPagesPromises.push(fetchPage(i));
    }

    const allPagesContent = await Promise.all(allPagesPromises);
    const newPhotos = allPagesContent.flat();

    setPhotos(newPhotos);
    await fetchAlbums();

    if (scrollPosition && mainContentRef.current) {
      requestAnimationFrame(() => {
        if(mainContentRef.current) {
          mainContentRef.current.scrollTop = scrollPosition;
        }
      });
    }
  }, [page, effectivePath, fetchAlbums, duplicatesPage, fetchDuplicates, setPhotos, mainContentRef]);

  return { refreshAndMaintainScroll };
}