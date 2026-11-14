import React, { useState } from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, fireEvent, act } from '@testing-library/react';
import PhotoGrid from './PhotoGrid';
import { localeWrapper } from '../test/testUtils.jsx';

function makePhotos(n) {
  return Array.from({ length: n }, (_, i) => ({
    id: `p${i}`,
    url: `/photo${i}.jpg`,
    fullDateTime: `2024-03-15T14:00:${String(50 - i * 5).padStart(2, '0')}`,
  }));
}

// Every photo-listing view is day-indexed now - wrap the fixture photos into a single day bucket,
// same shape hooks/useDayIndexedPhotos.js produces, so PhotoGrid renders exactly as it does under
// App.jsx.
function makeDayIndex(photos) {
  return {
    days: [{ day: 'd1', count: photos.length }],
    loadingDays: false,
    photosByDay: { d1: photos },
    dayPageState: { d1: { isLast: true } },
    loadDayPage: () => {},
  };
}

// Thin harness holding real React state so PhotoGrid's controlled selectedIds prop
// behaves exactly as it does under App.jsx, instead of hand-rolling rerenders.
function Harness({
  photos,
  dayIndex,
  handleMoveToGarbage,
  handleUnlinkDuplicate,
  handleReorderPhotos,
  setSelectedPhoto = () => {},
}) {
  const [selectedIds, setSelectedIds] = useState([]);
  return (
    <PhotoGrid
      dayIndex={dayIndex || makeDayIndex(photos)}
      selectedIds={selectedIds}
      setSelectedIds={setSelectedIds}
      setSelectedPhoto={setSelectedPhoto}
      isLightboxOpen={false}
      handleMoveToGarbage={handleMoveToGarbage}
      handleUnlinkDuplicate={handleUnlinkDuplicate}
      handleReorderPhotos={handleReorderPhotos}
    />
  );
}

function renderHarness(photos, handleMoveToGarbage, handleUnlinkDuplicate, options = {}) {
  const utils = render(
    <Harness
      photos={photos}
      dayIndex={options.dayIndex}
      handleMoveToGarbage={handleMoveToGarbage}
      handleUnlinkDuplicate={handleUnlinkDuplicate}
      handleReorderPhotos={options.handleReorderPhotos}
      setSelectedPhoto={options.setSelectedPhoto}
    />,
    { wrapper: localeWrapper }
  );
  const cards = () => utils.container.querySelectorAll('.photo-card');
  return { ...utils, cards };
}

describe('PhotoGrid shift+click range selection', () => {
  it('selects the contiguous range between the anchor and a shift-clicked photo', () => {
    const { cards } = renderHarness(makePhotos(5));

    // Start selection mode via the checkbox overlay on the first photo.
    fireEvent.click(cards()[0].querySelector('.select-overlay'));
    expect(cards()[0]).toHaveClass('selected');

    // Shift+click the fourth photo: expect p0..p3 all selected.
    fireEvent.click(cards()[3], { shiftKey: true });

    expect(cards()[0]).toHaveClass('selected');
    expect(cards()[1]).toHaveClass('selected');
    expect(cards()[2]).toHaveClass('selected');
    expect(cards()[3]).toHaveClass('selected');
    expect(cards()[4]).not.toHaveClass('selected');
  });

  it('extends an existing selection rather than replacing it', () => {
    const { cards } = renderHarness(makePhotos(6));

    fireEvent.click(cards()[4].querySelector('.select-overlay')); // anchor at index 4
    fireEvent.click(cards()[1], { shiftKey: true }); // range 1..4

    [1, 2, 3, 4].forEach(i => expect(cards()[i]).toHaveClass('selected'));
    expect(cards()[0]).not.toHaveClass('selected');
    expect(cards()[5]).not.toHaveClass('selected');
  });

  it('a plain click without shift just toggles a single card, not a range', () => {
    const { cards } = renderHarness(makePhotos(5));

    fireEvent.click(cards()[0].querySelector('.select-overlay'));
    fireEvent.click(cards()[3]); // no shiftKey

    expect(cards()[0]).toHaveClass('selected');
    expect(cards()[3]).toHaveClass('selected');
    expect(cards()[1]).not.toHaveClass('selected');
    expect(cards()[2]).not.toHaveClass('selected');
  });

  it('shift+click with no prior anchor behaves like a normal single toggle', () => {
    const { cards } = renderHarness(makePhotos(5));

    // Use the checkbox overlay, not the card body: with nothing selected yet, a plain
    // click on the card body opens the Lightbox instead of selecting (pre-existing,
    // unrelated to shift) — the overlay always toggles selection regardless.
    fireEvent.click(cards()[2].querySelector('.select-overlay'), { shiftKey: true });

    expect(cards()[2]).toHaveClass('selected');
    expect(cards()[0]).not.toHaveClass('selected');
    expect(cards()[1]).not.toHaveClass('selected');
  });
});

// Clicking a "×N similar" badge opens DuplicateClusterModal.jsx (a popup showing every member
// of that cluster, no pagination) instead of the older expand-in-place row — see TODO.md.
describe('PhotoGrid duplicate grouping', () => {
  function makePhotosWithDuplicate() {
    const dup = { id: 'p1-dup', url: '/photo1-dup.jpg' };
    return [
      { id: 'p0', url: '/photo0.jpg' },
      { id: 'p1', url: '/photo1.jpg', duplicates: [dup] },
    ];
  }

  function makePhotosWithTripleDuplicate() {
    const dup1 = { id: 'p1-dup1', url: '/photo1-dup1.jpg' };
    const dup2 = { id: 'p1-dup2', url: '/photo1-dup2.jpg' };
    return [
      { id: 'p1', url: '/photo1.jpg', duplicates: [dup1, dup2] },
    ];
  }

  it('renders a single collapsed card with a badge instead of two separate cards, popup closed', () => {
    const { cards, container } = renderHarness(makePhotosWithDuplicate());

    // p0 (no duplicates) + one collapsed cluster card for p1/p1-dup = 2 cards, not 3.
    expect(cards().length).toBe(2);
    expect(container.querySelector('.duplicate-badge')).toBeTruthy();
    expect(container.querySelector('.duplicate-modal-content')).toBeFalsy();
  });

  it('opens a popup flickering the suggested keeper against the other member, and the X closes it', () => {
    const { container } = renderHarness(makePhotosWithDuplicate());

    fireEvent.click(container.querySelector('.duplicate-badge'));

    expect(container.querySelector('.duplicate-modal-content')).toBeTruthy();
    expect(container.querySelectorAll('.duplicate-rail-thumb').length).toBe(2); // p1 + p1-dup
    // Neither fixture photo has width/height, so they tie on resolution and the first
    // (p1) is the suggested keeper — the flicker stage starts showing it full-size.
    expect(container.querySelector('.flicker-stage img[src="/api/photos/p1/original"]')).toBeTruthy();

    fireEvent.click(container.querySelector('.duplicate-modal-close'));

    expect(container.querySelector('.duplicate-modal-content')).toBeFalsy();
  });

  it('toggles the flicker stage between the keeper and the compared member', () => {
    const { container } = renderHarness(makePhotosWithDuplicate());
    fireEvent.click(container.querySelector('.duplicate-badge'));

    expect(container.querySelector('.flicker-stage img[src="/api/photos/p1/original"]')).toBeTruthy();

    fireEvent.click(container.querySelector('.flicker-stage'));
    expect(container.querySelector('.flicker-stage img[src="/api/photos/p1-dup/original"]')).toBeTruthy();

    fireEvent.click(container.querySelector('.flicker-stage'));
    expect(container.querySelector('.flicker-stage img[src="/api/photos/p1/original"]')).toBeTruthy();
  });

  it('cycles the flicker stage through every member of a cluster larger than 2, not just two', () => {
    const { container } = renderHarness(makePhotosWithTripleDuplicate());
    fireEvent.click(container.querySelector('.duplicate-badge'));

    expect(container.querySelectorAll('.duplicate-rail-thumb').length).toBe(3);
    expect(container.querySelector('.flicker-stage img[src="/api/photos/p1/original"]')).toBeTruthy();

    fireEvent.click(container.querySelector('.flicker-stage'));
    expect(container.querySelector('.flicker-stage img[src="/api/photos/p1-dup1/original"]')).toBeTruthy();

    fireEvent.click(container.querySelector('.flicker-stage'));
    expect(container.querySelector('.flicker-stage img[src="/api/photos/p1-dup2/original"]')).toBeTruthy();

    // Wraps back around to the first member instead of getting stuck alternating between two.
    fireEvent.click(container.querySelector('.flicker-stage'));
    expect(container.querySelector('.flicker-stage img[src="/api/photos/p1/original"]')).toBeTruthy();
  });

  it('clicking a member thumbnail shows that photo directly and marks it as the active thumbnail', () => {
    const { container } = renderHarness(makePhotosWithDuplicate());
    fireEvent.click(container.querySelector('.duplicate-badge'));

    const thumbs = container.querySelectorAll('.duplicate-rail-thumb');
    const keeperThumb = Array.from(thumbs).find(el => el.querySelector('img[src="/photo1.jpg"]'));
    const dupThumb = Array.from(thumbs).find(el => el.querySelector('img[src="/photo1-dup.jpg"]'));

    expect(keeperThumb).toHaveClass('active');
    expect(dupThumb).not.toHaveClass('active');

    fireEvent.click(dupThumb);

    expect(container.querySelector('.flicker-stage img[src="/api/photos/p1-dup/original"]')).toBeTruthy();
    expect(dupThumb).toHaveClass('active');
    expect(keeperThumb).not.toHaveClass('active');
  });

  it('shows a move-to-garbage icon on each member in the popup, and it removes just that one photo', () => {
    const handleMoveToGarbage = vi.fn();
    const { container } = renderHarness(makePhotosWithDuplicate(), handleMoveToGarbage);

    // Closed: no per-member remove icon anywhere yet.
    expect(container.querySelector('.duplicate-remove-btn')).toBeFalsy();

    fireEvent.click(container.querySelector('.duplicate-badge'));

    const removeButtons = container.querySelectorAll('.duplicate-remove-btn');
    expect(removeButtons.length).toBe(2); // one per member in the popup

    const dupThumb = Array.from(container.querySelectorAll('.duplicate-rail-thumb'))
      .find(el => el.querySelector('img[src="/photo1-dup.jpg"]'));
    fireEvent.click(dupThumb.querySelector('.duplicate-remove-btn'));

    expect(handleMoveToGarbage).toHaveBeenCalledWith(['p1-dup']);
    expect(handleMoveToGarbage).toHaveBeenCalledTimes(1);
  });

  it('shows trash and unlink icons on the main stage, acting on whichever member is shown', () => {
    const handleMoveToGarbage = vi.fn();
    const handleUnlinkDuplicate = vi.fn();
    const { container } = renderHarness(makePhotosWithDuplicate(), handleMoveToGarbage, handleUnlinkDuplicate);

    fireEvent.click(container.querySelector('.duplicate-badge'));

    const stageTrashBtn = container.querySelector('.flicker-icon-btn-danger');
    const stageUnlinkBtn = container.querySelector('.flicker-photo-actions button:not(.flicker-icon-btn-danger)');
    expect(stageTrashBtn).toBeTruthy();
    expect(stageUnlinkBtn).toBeTruthy();

    // Stage starts on the keeper (p1); unlink must send both the photo being unlinked and every
    // member of the group as currently known client-side (backend confirms the group hasn't
    // changed since).
    fireEvent.click(stageUnlinkBtn);
    expect(handleUnlinkDuplicate).toHaveBeenCalledWith('p1', ['p1', 'p1-dup']);

    fireEvent.click(stageTrashBtn);
    expect(handleMoveToGarbage).toHaveBeenCalledWith(['p1']);
  });

  // Regression: garbage-ing the SUGGESTED KEEP (p1, the primary) used to close the modal
  // entirely, because the item it was anchored to (found by primary id) vanished from `groups`
  // once p1 was removed - see utils/photoListUpdates.js's promotion fix. The modal should instead
  // stay open, now showing whichever member survived (p1-dup, promoted to primary).
  it('keeps the modal open, now anchored to the promoted member, when the primary itself is garbage\'d', () => {
    const { container, rerender } = render(
      <Harness photos={makePhotosWithDuplicate()} />,
      { wrapper: localeWrapper }
    );

    fireEvent.click(container.querySelector('.duplicate-badge'));
    expect(container.querySelector('.duplicate-modal-content')).toBeTruthy();

    // What removeFromFlatPhotos produces after garbage-ing p1: p1-dup promoted to take its place,
    // with no duplicates of its own left (this was the whole cluster).
    rerender(<Harness photos={[{ id: 'p1-dup', url: '/photo1-dup.jpg', duplicates: [] }]} />);

    expect(container.querySelector('.duplicate-modal-content')).toBeTruthy();
    expect(container.querySelector('.flicker-stage img[src="/api/photos/p1-dup/original"]')).toBeTruthy();
  });
});

// Regression/feature test for the day-count look-ahead prefetch (user request, 2026-09-11):
// reaching a day's sentinel should also eagerly start loading the day 2 positions further down,
// not just that day's own next page - see PhotoGrid.jsx's IntersectionObserver callback comment.
// The shared jsdom IntersectionObserver stub (test/setup.js) never actually fires its callback, so
// this test swaps in a local one that captures it for manual, synchronous invocation.
describe('PhotoGrid day-index prefetch look-ahead', () => {
  it('eagerly loads the day 2 positions ahead when a day\'s sentinel intersects', () => {
    const loadDayPage = vi.fn();
    const dayIndex = {
      days: [{ day: 'd1' }, { day: 'd2' }, { day: 'd3' }, { day: 'd4' }],
      loadingDays: false,
      photosByDay: {},
      dayPageState: {},
      loadDayPage,
    };

    let capturedCallback;
    class CapturingObserver {
      constructor(callback) { capturedCallback = callback; }
      observe() {}
      unobserve() {}
      disconnect() {}
    }
    const OriginalIntersectionObserver = global.IntersectionObserver;
    global.IntersectionObserver = CapturingObserver;

    try {
      render(
        <PhotoGrid
          dayIndex={dayIndex}
          selectedIds={[]}
          setSelectedIds={() => {}}
          setSelectedPhoto={() => {}}
          isLightboxOpen={false}
          handleMoveToGarbage={() => {}}
          handleUnlinkDuplicate={() => {}}
        />,
        { wrapper: localeWrapper }
      );

      const sentinel = document.createElement('div');
      sentinel.setAttribute('data-day-sentinel', 'd1');
      capturedCallback([{ isIntersecting: true, target: sentinel }]);

      expect(loadDayPage).toHaveBeenCalledWith('d1'); // the reached day's own (first) page
      expect(loadDayPage).toHaveBeenCalledWith('d3'); // look-ahead: 2 positions past d1
      expect(loadDayPage).not.toHaveBeenCalledWith('d2');
      expect(loadDayPage).not.toHaveBeenCalledWith('d4');
    } finally {
      global.IntersectionObserver = OriginalIntersectionObserver;
    }
  });

  it('does not look ahead past the end of the day list', () => {
    const loadDayPage = vi.fn();
    const dayIndex = {
      days: [{ day: 'd1' }, { day: 'd2' }],
      loadingDays: false,
      photosByDay: {},
      dayPageState: {},
      loadDayPage,
    };

    let capturedCallback;
    class CapturingObserver {
      constructor(callback) { capturedCallback = callback; }
      observe() {}
      unobserve() {}
      disconnect() {}
    }
    const OriginalIntersectionObserver = global.IntersectionObserver;
    global.IntersectionObserver = CapturingObserver;

    try {
      render(
        <PhotoGrid
          dayIndex={dayIndex}
          selectedIds={[]}
          setSelectedIds={() => {}}
          setSelectedPhoto={() => {}}
          isLightboxOpen={false}
          handleMoveToGarbage={() => {}}
          handleUnlinkDuplicate={() => {}}
        />,
        { wrapper: localeWrapper }
      );

      const sentinel = document.createElement('div');
      sentinel.setAttribute('data-day-sentinel', 'd2'); // last day - nothing 2 positions past it
      capturedCallback([{ isIntersecting: true, target: sentinel }]);

      expect(loadDayPage).toHaveBeenCalledTimes(1);
      expect(loadDayPage).toHaveBeenCalledWith('d2');
    } finally {
      global.IntersectionObserver = OriginalIntersectionObserver;
    }
  });
});

describe('PhotoGrid reorder selection, 2s hold drag, and timeline margin', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('adds has-timeline-scrubber class on .content-body when 2+ days are present', () => {
    const { container } = renderHarness(makePhotos(2), undefined, undefined, {
      dayIndex: {
        days: [{ day: '2024-03-15', count: 1 }, { day: '2024-03-14', count: 1 }],
        loadingDays: false,
        photosByDay: {
          '2024-03-15': [{ id: 'p0', url: '/photo0.jpg', fullDateTime: '2024-03-15T14:00:50' }],
          '2024-03-14': [{ id: 'p1', url: '/photo1.jpg', fullDateTime: '2024-03-14T14:00:45' }],
        },
        dayPageState: { '2024-03-15': { isLast: true }, '2024-03-14': { isLast: true } },
        loadDayPage: () => {},
      },
    });
    expect(container.querySelector('.content-body')).toHaveClass('has-timeline-scrubber');

    const single = renderHarness(makePhotos(2));
    expect(single.container.querySelector('.content-body')).not.toHaveClass('has-timeline-scrubber');
  });

  it('clicking the move button toggles reorder selection for multiple assets within one day', () => {
    const handleReorderPhotos = vi.fn();
    const { cards, container } = renderHarness(null, undefined, undefined, {
      handleReorderPhotos,
      dayIndex: {
        days: [{ day: 'd1', count: 3 }, { day: 'd2', count: 1 }],
        loadingDays: false,
        photosByDay: {
          d1: makePhotos(3),
          d2: [{ id: 'other', url: '/other.jpg', fullDateTime: '2024-03-14T10:00:00' }],
        },
        dayPageState: { d1: { isLast: true }, d2: { isLast: true } },
        loadDayPage: () => {},
      },
    });

    // Click move button on first and third assets in d1
    const firstBtn = cards()[0].querySelector('.drag-handle');
    const secondBtn = cards()[1].querySelector('.drag-handle');
    const thirdBtn = cards()[2].querySelector('.drag-handle');

    fireEvent.click(firstBtn);
    expect(cards()[0]).toHaveClass('reorder-selected');
    expect(firstBtn).toHaveClass('reorder-selected');
    expect(firstBtn).toHaveAttribute('aria-pressed', 'true');
    expect(firstBtn.querySelector('.drag-handle-check')).toBeTruthy();

    fireEvent.click(thirdBtn);
    expect(cards()[0]).toHaveClass('reorder-selected');
    expect(cards()[1]).not.toHaveClass('reorder-selected');
    expect(cards()[2]).toHaveClass('reorder-selected');
    expect(secondBtn).not.toHaveClass('reorder-selected');

    // Other day's move buttons are hidden while d1 has active reorder selection
    expect(cards()[3].querySelector('.drag-handle')).toBeNull();

    // Unchecking both resets reorderDay and reveals d2's move button again
    fireEvent.click(firstBtn);
    fireEvent.click(thirdBtn);
    expect(cards()[0]).not.toHaveClass('reorder-selected');
    expect(cards()[2]).not.toHaveClass('reorder-selected');
    expect(container.querySelectorAll('.photo-card .drag-handle').length).toBe(4);
  });

  it('starts dragging only after holding mouse for 2000ms and moves all checked assets together', () => {
    vi.useFakeTimers();
    const handleReorderPhotos = vi.fn();
    const setSelectedPhoto = vi.fn();
    const { cards, container } = renderHarness(makePhotos(3), undefined, undefined, {
      handleReorderPhotos,
      setSelectedPhoto,
    });

    // Mock bounding rects cleanly for horizontal row: p0 (0..100), p1 (100..200), p2 (200..300)
    cards().forEach((card, idx) => {
      card.getBoundingClientRect = () => ({
        left: idx * 100,
        right: (idx + 1) * 100,
        top: 0,
        bottom: 100,
        width: 100,
        height: 100,
      });
    });

    // Pre-select p0 and p1 via their move buttons
    fireEvent.click(cards()[0].querySelector('.drag-handle'));
    fireEvent.click(cards()[1].querySelector('.drag-handle'));

    // Hold on p0 for 1999ms -> should NOT start drag yet
    fireEvent.mouseDown(cards()[0], { button: 0, clientX: 50, clientY: 50 });
    act(() => {
      vi.advanceTimersByTime(1999);
    });
    expect(container.querySelector('[data-testid="reorder-drag-preview"]')).toBeNull();

    // Reach 2000ms -> drag becomes active and visual preview badge appears with count 2
    act(() => {
      vi.advanceTimersByTime(1);
    });
    const preview = container.querySelector('[data-testid="reorder-drag-preview"]');
    expect(preview).toBeTruthy();
    expect(preview.querySelector('.reorder-drag-count-badge')?.textContent).toBe('2');
    expect(cards()[0]).toHaveClass('dragging');
    expect(cards()[1]).toHaveClass('dragging');

    // Drag over right half of p2 (clientX = 275 -> 'after' p2) and release
    fireEvent.mouseMove(window, { clientX: 275, clientY: 50 });
    expect(cards()[2].querySelector('.drop-indicator-after')).toBeTruthy();

    fireEvent.mouseUp(window, { clientX: 275, clientY: 50 });

    // Releasing must commit both p0 and p1 after p2 (14:00:40), without opening Lightbox
    expect(handleReorderPhotos).toHaveBeenCalledTimes(1);
    expect(handleReorderPhotos).toHaveBeenCalledWith([
      { photoId: 'p0', newDate: '2024-03-15T14:00:39.000' },
      { photoId: 'p1', newDate: '2024-03-15T14:00:38.000' },
    ]);

    // A follow-up click synthesized after mouseup must not open the lightbox
    fireEvent.click(cards()[0]);
    expect(setSelectedPhoto).not.toHaveBeenCalled();

    // Preview and reorder selection clear after drop
    expect(container.querySelector('[data-testid="reorder-drag-preview"]')).toBeNull();
    expect(cards()[0]).not.toHaveClass('reorder-selected');
    expect(cards()[1]).not.toHaveClass('reorder-selected');
  });

  it('starts dragging on 2000ms touch hold and cancels hold if touch moves > 10px before 2s', () => {
    vi.useFakeTimers();
    const handleReorderPhotos = vi.fn();
    const { cards, container } = renderHarness(makePhotos(2), undefined, undefined, {
      handleReorderPhotos,
    });

    cards().forEach((card, idx) => {
      card.getBoundingClientRect = () => ({
        left: idx * 100,
        right: (idx + 1) * 100,
        top: 0,
        bottom: 100,
        width: 100,
        height: 100,
      });
    });

    // Moving > 10px before 2s cancels hold timer (so scrolling stays smooth)
    fireEvent.touchStart(cards()[0], { touches: [{ clientX: 40, clientY: 40 }] });
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    fireEvent.touchMove(window, { touches: [{ clientX: 40, clientY: 60 }] });
    act(() => {
      vi.advanceTimersByTime(1500);
    });
    expect(container.querySelector('[data-testid="reorder-drag-preview"]')).toBeNull();
    fireEvent.touchEnd(window);

    // Holding still for 2000ms via touch starts drag and commits drop
    fireEvent.touchStart(cards()[0], { touches: [{ clientX: 50, clientY: 50 }] });
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(container.querySelector('[data-testid="reorder-drag-preview"]')).toBeTruthy();

    fireEvent.touchMove(window, { touches: [{ clientX: 180, clientY: 50 }] });
    fireEvent.touchEnd(window);

    expect(handleReorderPhotos).toHaveBeenCalledWith([
      { photoId: 'p0', newDate: '2024-03-15T14:00:44.000' },
    ]);
  });
});
