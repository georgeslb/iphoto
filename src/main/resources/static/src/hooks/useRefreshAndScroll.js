import { useCallback } from 'react';

export function useRefreshAndScroll(
  mainContentRef,
  page,
  effectivePath,
  fetchAlbums,
  duplicatesPage,
  fetchDuplicates,
  setPhotos, // From usePhotoData
  fetchPhotos // Also from usePhotoData, to be used directly
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

    // Fetch all pages up to the current page using the provided fetchPhotos
    const allPagesPromises = [];
    for (let i = 0; i <= currentPage; i++) {
      // fetchPhotos already handles setting photos, but we need to collect them
      // to ensure correct scroll restoration and state consistency.
      // For now, we'll call it with reset = false and then manually setPhotos at the end.
      // A more ideal solution might be to have fetchPhotos return the new photos.
      // For this refactor, let's adapt to its current signature.
      // This part needs careful consideration to avoid double-setting state or race conditions.
      // A simpler approach for refresh is to refetch all needed data and then set it.

      // Re-calling fetchPhotos for each page is inefficient.
      // Let's modify this to fetch all needed data in one go if possible,
      // or ensure fetchPhotos can return the data it fetched.
      // Given fetchPhotos directly modifies `photos` state, we need to adjust.

      // For now, let's assume fetchPhotos can be called to get a specific page's content
      // without immediately setting the global photos state, or we adapt fetchPhotos.
      // Let's assume fetchPhotos *can* return the content.
      // If not, we'd need a separate internal fetch function here.

      // Re-evaluating: The original fetchPhotos already sets `photos` state.
      // To avoid race conditions and ensure correct state, we should probably
      // fetch all data needed for the current view and then set `photos` once.

      // Let's adjust the logic to fetch all pages needed for the current view.
      // The `fetchPhotos` function itself already appends/resets.
      // For `refreshAndMaintainScroll`, we need to fetch all pages up to `currentPage`
      // and then set the photos array.

      // Let's create a temporary fetch function here that returns data.
      const tempFetchPage = async (pageNum) => {
        let tempEndpoint = '/api/photos';
        let tempSort = 'dateTaken,desc';

        if (type === 'garbage') {
          tempEndpoint = '/api/photos/garbage';
          tempSort = 'garbageAt,desc';
        } else if (type === 'album' && albumId) {
          tempEndpoint = `/api/albums/${albumId}/photos`;
        }

        const res = await fetch(`${tempEndpoint}?page=${pageNum}&size=50&sort=${tempSort}`);
        if (!res.ok) return [];
        const data = await res.json();
        return Array.isArray(data) ? data : (data.content || []);
      };
      allPagesPromises.push(tempFetchPage(i));
    }

    const allPagesContent = await Promise.all(allPagesPromises);
    const newPhotos = allPagesContent.flat();

    setPhotos(newPhotos); // Set photos from usePhotoData
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