import { useEffect, useRef } from 'react';

export function useInitialDataFetch(
  effectivePath,
  isLightboxOpen,
  setSelectedIds,
  setDuplicatesPage,
  fetchDuplicates
  // Removed setMapAssets from parameters
) {
  const prevSectionRef = useRef(null);

  useEffect(() => {
    const currentSection = effectivePath.split('/')[1] || 'library';
    const prevSection = prevSectionRef.current;
    prevSectionRef.current = currentSection;

    if (isLightboxOpen) return;

    // Prevent resetting state if we are just moving within the duplicates section (e.g. from /duplicates to /duplicates/hash)
    if (currentSection === 'duplicates' && prevSection === 'duplicates') {
      return;
    }

    setSelectedIds([]);

    // Removed map assets fetching logic from here, as useMapAssets handles it
    if (effectivePath.startsWith('/duplicates')) {
      const urlParams = new URLSearchParams(window.location.search);
      const pageParam = parseInt(urlParams.get('page'), 10);
      const targetPage = !isNaN(pageParam) ? pageParam : 0;

      setDuplicatesPage(targetPage);
      fetchDuplicates(targetPage);
    }
  }, [effectivePath, isLightboxOpen, setSelectedIds, setDuplicatesPage, fetchDuplicates]); // Removed setMapAssets from dependencies

  return { prevSectionRef };
}