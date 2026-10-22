import { useEffect, useRef } from 'react';

// Generous top scroll zone (220px) to account for sticky header (73px) + sticky day header / margins,
// so scrolling initiates comfortably before the user reaches the top bar.
const SCROLL_ZONE_TOP_PX = 220;
const SCROLL_ZONE_BOTTOM_PX = 150;
const MAX_SPEED_PX = 16;

/**
 * Automatically scrolls the container/window when dragging an asset near the top or bottom edges.
 * Also ensures the browser does not display the forbidden (🚫) cursor by preventing default on dragover.
 *
 * @param {boolean} isDragging Whether a drag-to-reorder operation is currently active.
 * @param {React.RefObject} containerRef Ref to an element within the scroll container (e.g. gridContainerRef).
 */
export function useDragAutoScroll(isDragging, containerRef) {
  const cursorYRef = useRef(null);
  const animFrameRef = useRef(null);

  useEffect(() => {
    if (!isDragging) {
      cursorYRef.current = null;
      if (animFrameRef.current) {
        cancelAnimationFrame(animFrameRef.current);
        animFrameRef.current = null;
      }
      return;
    }

    const getScrollContainer = () => {
      if (containerRef?.current) {
        return containerRef.current.closest('.main-content') || containerRef.current;
      }
      return document.querySelector('.main-content') || window;
    };

    const container = getScrollContainer();

    const handleDragOver = (e) => {
      // Prevent default to signal a valid drop area across the window, preventing the browser
      // from showing the forbidden (🚫) cursor while dragging.
      e.preventDefault();
      if (e.dataTransfer) {
        try {
          e.dataTransfer.dropEffect = 'move';
        } catch (_) {
          // Ignore in environments where dataTransfer is read-only
        }
      }
      cursorYRef.current = e.clientY;
    };

    const handleDragEnd = () => {
      cursorYRef.current = null;
    };

    // Non-passive listener so e.preventDefault() succeeds
    window.addEventListener('dragover', handleDragOver);
    window.addEventListener('dragend', handleDragEnd);

    const step = () => {
      const cursorY = cursorYRef.current;
      if (cursorY !== null && container) {
        let top = 0;
        let bottom = window.innerHeight;

        if (container.getBoundingClientRect) {
          const rect = container.getBoundingClientRect();
          top = Math.max(0, rect.top);
          bottom = Math.min(window.innerHeight, rect.bottom);
        }

        const availableHeight = Math.max(0, bottom - top);
        const zoneTop = Math.min(SCROLL_ZONE_TOP_PX, availableHeight * 0.35);
        const zoneBottom = Math.min(SCROLL_ZONE_BOTTOM_PX, availableHeight * 0.35);

        let speed = 0;
        if (zoneTop > 0 && cursorY < top + zoneTop) {
          // Near the top edge -> scroll up
          const intensity = Math.min(1, Math.max(0, (top + zoneTop - cursorY) / zoneTop));
          speed = -Math.max(2, Math.round(intensity * MAX_SPEED_PX));
        } else if (zoneBottom > 0 && cursorY > bottom - zoneBottom) {
          // Near the bottom edge -> scroll down
          const intensity = Math.min(1, Math.max(0, (cursorY - (bottom - zoneBottom)) / zoneBottom));
          speed = Math.max(2, Math.round(intensity * MAX_SPEED_PX));
        }

        if (speed !== 0) {
          if (typeof container.scrollBy === 'function') {
            container.scrollBy(0, speed);
          } else if (container.scrollTop !== undefined) {
            container.scrollTop += speed;
          }
        }
      }

      animFrameRef.current = requestAnimationFrame(step);
    };

    animFrameRef.current = requestAnimationFrame(step);

    return () => {
      window.removeEventListener('dragover', handleDragOver);
      window.removeEventListener('dragend', handleDragEnd);
      if (animFrameRef.current) {
        cancelAnimationFrame(animFrameRef.current);
        animFrameRef.current = null;
      }
      cursorYRef.current = null;
    };
  }, [isDragging, containerRef]);
}
