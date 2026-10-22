import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useDragAutoScroll } from './useDragAutoScroll';

describe('useDragAutoScroll', () => {
  let mockScrollContainer;
  let containerRef;
  let rafCallbacks = [];
  let nextRafId = 1;

  beforeEach(() => {
    rafCallbacks = [];
    nextRafId = 1;

    vi.stubGlobal('requestAnimationFrame', (cb) => {
      const id = nextRafId++;
      rafCallbacks.push({ id, cb });
      return id;
    });

    vi.stubGlobal('cancelAnimationFrame', (id) => {
      rafCallbacks = rafCallbacks.filter(item => item.id !== id);
    });

    mockScrollContainer = {
      getBoundingClientRect: vi.fn(() => ({
        top: 0,
        bottom: 800,
        left: 0,
        right: 1000,
        width: 1000,
        height: 800,
      })),
      scrollBy: vi.fn(),
      scrollTop: 100,
      closest: vi.fn(() => mockScrollContainer),
    };

    containerRef = { current: mockScrollContainer };
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  function flushRaf() {
    const current = [...rafCallbacks];
    rafCallbacks = [];
    current.forEach(item => item.cb(performance.now()));
  }

  it('does nothing when isDragging is false', () => {
    renderHook(() => useDragAutoScroll(false, containerRef));

    window.dispatchEvent(new MouseEvent('dragover', { clientY: 10 }));
    flushRaf();

    expect(mockScrollContainer.scrollBy).not.toHaveBeenCalled();
  });

  it('scrolls up when dragging near the top edge and calls preventDefault', () => {
    const { unmount } = renderHook(() => useDragAutoScroll(true, containerRef));

    // Simulate dragover at clientY = 50 (well within top scroll zone of 220px)
    const dragOverEvent = new Event('dragover', { cancelable: true });
    dragOverEvent.clientY = 50;
    dragOverEvent.dataTransfer = { dropEffect: 'none' };
    window.dispatchEvent(dragOverEvent);

    expect(dragOverEvent.defaultPrevented).toBe(true);
    expect(dragOverEvent.dataTransfer.dropEffect).toBe('move');

    flushRaf();

    expect(mockScrollContainer.scrollBy).toHaveBeenCalled();
    expect(mockScrollContainer.scrollBy.mock.calls[0][1]).toBeLessThan(0);

    unmount();
  });

  it('scrolls down when dragging near the bottom edge', () => {
    const { unmount } = renderHook(() => useDragAutoScroll(true, containerRef));

    // Simulate dragover at clientY = 750 (near bottom edge of 800, within bottom zone of 150px)
    const dragOverEvent = new Event('dragover', { cancelable: true });
    dragOverEvent.clientY = 750;
    window.dispatchEvent(dragOverEvent);

    flushRaf();

    expect(mockScrollContainer.scrollBy).toHaveBeenCalled();
    expect(mockScrollContainer.scrollBy.mock.calls[0][1]).toBeGreaterThan(0);

    unmount();
  });

  it('does not scroll when cursor is in the middle', () => {
    const { unmount } = renderHook(() => useDragAutoScroll(true, containerRef));

    // Simulate dragover at clientY = 400 (middle of 0..800)
    const dragOverEvent = new Event('dragover');
    dragOverEvent.clientY = 400;
    window.dispatchEvent(dragOverEvent);

    flushRaf();

    expect(mockScrollContainer.scrollBy).not.toHaveBeenCalled();

    unmount();
  });

  it('stops scrolling when dragend fires', () => {
    const { unmount } = renderHook(() => useDragAutoScroll(true, containerRef));

    const dragOverEvent = new Event('dragover');
    dragOverEvent.clientY = 20;
    window.dispatchEvent(dragOverEvent);

    flushRaf();
    expect(mockScrollContainer.scrollBy).toHaveBeenCalledTimes(1);

    // Fire dragend
    window.dispatchEvent(new Event('dragend'));
    mockScrollContainer.scrollBy.mockClear();

    flushRaf();
    expect(mockScrollContainer.scrollBy).not.toHaveBeenCalled();

    unmount();
  });
});
