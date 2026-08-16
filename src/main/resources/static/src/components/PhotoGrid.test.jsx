import React, { useState } from 'react';
import { describe, it, expect } from 'vitest';
import { render, fireEvent } from '@testing-library/react';
import PhotoGrid from './PhotoGrid';
import { localeWrapper } from '../test/testUtils.jsx';

function makePhotos(n) {
  return Array.from({ length: n }, (_, i) => ({ id: `p${i}`, url: `/photo${i}.jpg` }));
}

// Thin harness holding real React state so PhotoGrid's controlled selectedIds prop
// behaves exactly as it does under App.jsx, instead of hand-rolling rerenders.
function Harness({ photos }) {
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
    />
  );
}

function renderHarness(photos) {
  const utils = render(<Harness photos={photos} />, { wrapper: localeWrapper });
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
