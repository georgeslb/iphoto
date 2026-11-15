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

    let attempts = 0;
    const restoreScroll = () => {
      const container = mainContentRef.current;

      if (!container) return;

      const maxScroll = Math.max(0, container.scrollHeight - container.clientHeight);
      const targetScroll = Math.min(scrollPositionRef.current, maxScroll);

      // Wait until layout is tall enough or we've retried enough frames
      if (container.scrollHeight <= scrollPositionRef.current && attempts < 15) {
        attempts++;
        requestAnimationFrame(restoreScroll);
        return;
      }

      container.scrollTop = targetScroll;
      hasRestoredScrollRef.current = true;
    };

    requestAnimationFrame(restoreScroll);
  }, [isLightboxOpen, photos, mainContentRef]);

  return { saveScrollPosition };
}