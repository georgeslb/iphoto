import React, { useState } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, fireEvent } from '@testing-library/react';
import PhotoGrid from './PhotoGrid';
import { localeWrapper } from '../test/testUtils.jsx';

function makePhotos(n) {
  return Array.from({ length: n }, (_, i) => ({ id: `p${i}`, url: `/photo${i}.jpg` }));
}

// Thin harness holding real React state so PhotoGrid's controlled selectedIds prop
// behaves exactly as it does under App.jsx, instead of hand-rolling rerenders.
function Harness({ photos, handleMoveToGarbage }) {
  const [selectedIds, setSelectedIds] = useState([]);
  return (
    <PhotoGrid
      photos={photos}
      hasMore={false}
      setPage={() => {}}
      selectedIds={selectedIds}
      setSelectedIds={setSelectedIds}
      setSelectedPhoto={() => {}}
      isLightboxOpen={false}
      handleMoveToGarbage={handleMoveToGarbage}
    />
  );
}

function renderHarness(photos, handleMoveToGarbage) {
  const utils = render(<Harness photos={photos} handleMoveToGarbage={handleMoveToGarbage} />, { wrapper: localeWrapper });
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

  it('renders a single collapsed card with a badge instead of two separate cards, popup closed', () => {
    const { cards, container } = renderHarness(makePhotosWithDuplicate());

    // p0 (no duplicates) + one collapsed cluster card for p1/p1-dup = 2 cards, not 3.
    expect(cards().length).toBe(2);
    expect(container.querySelector('.duplicate-badge')).toBeTruthy();
    expect(container.querySelector('.duplicate-modal-content')).toBeFalsy();
  });

  it('opens a popup showing every member of the cluster, and the X closes it', () => {
    const { container } = renderHarness(makePhotosWithDuplicate());

    fireEvent.click(container.querySelector('.duplicate-badge'));

    expect(container.querySelector('.duplicate-modal-content')).toBeTruthy();
    expect(container.querySelectorAll('.duplicate-modal-item').length).toBe(2); // p1 + p1-dup
    expect(container.querySelector('.duplicate-modal-item img[src="/api/photos/p1-dup/original"]')).toBeTruthy();

    fireEvent.click(container.querySelector('.duplicate-modal-close'));

    expect(container.querySelector('.duplicate-modal-content')).toBeFalsy();
  });

  it('shows a move-to-garbage icon on each member in the popup, and it removes just that one photo', () => {
    const handleMoveToGarbage = vi.fn();
    const { container } = renderHarness(makePhotosWithDuplicate(), handleMoveToGarbage);

    // Closed: no per-member remove icon anywhere yet.
    expect(container.querySelector('.duplicate-remove-btn')).toBeFalsy();

    fireEvent.click(container.querySelector('.duplicate-badge'));

    const removeButtons = container.querySelectorAll('.duplicate-remove-btn');
    expect(removeButtons.length).toBe(2); // one per member in the popup

    const dupItem = Array.from(container.querySelectorAll('.duplicate-modal-item'))
      .find(el => el.querySelector('img[src="/api/photos/p1-dup/original"]'));
    fireEvent.click(dupItem.querySelector('.duplicate-remove-btn'));

    expect(handleMoveToGarbage).toHaveBeenCalledWith(['p1-dup']);
    expect(handleMoveToGarbage).toHaveBeenCalledTimes(1);
  });
});
