import { useRef, useEffect } from 'react';

export function useScrollRestoration(mainContentRef, isLightboxOpen, photos) {
  const scrollPositionRef = useRef(0);
  const hasRestoredScrollRef = useRef(false);

  const saveScrollPosition = () => {
    if (mainContentRef.current) {
      scrollPositionRef.current = mainContentRef.current.scrollTop;
      hasRestoredScrollRef.current = false;
    }
  };

  useEffect(() => {
    if (
      isLightboxOpen ||
      !mainContentRef.current ||
      scrollPositionRef.current === 0 ||
      hasRestoredScrollRef.current
    ) {
      return;
    }

    const restoreScroll = () => {
      const container = mainContentRef.current;

      if (!container) return;

      // Wait until layout is tall enough
      if (container.scrollHeight <= scrollPositionRef.current) {
        requestAnimationFrame(restoreScroll);
        return;
      }

      container.scrollTop = scrollPositionRef.current;
      hasRestoredScrollRef.current = true;
    };

    requestAnimationFrame(restoreScroll);
  }, [isLightboxOpen, photos, mainContentRef]);

  return { saveScrollPosition };
}