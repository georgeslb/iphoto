import { describe, it, expect } from 'vitest';
import { computeReorderDates } from './photoReorder';

// day (yyyy-MM-dd) doesn't matter for the plain cases below, only relative order/spacing within
// it - using a fixed day throughout keeps the fixtures readable.
const DAY = '2024-03-15';
function photo(id, time) {
  return { id, fullDateTime: `${DAY}T${time}` };
}

describe('computeReorderDates', () => {
  // The exact worked example this feature's math was confirmed against: P displayed first
  // (14:00:10), Q displayed second (14:00:05); dropping X between them should land it at
  // 14:00:06 - Q's time + 1 second, NOT P's time + 1 second.
  it('anchors off the OLDER ("after") neighbor, not the newer one', () => {
    const p = photo('p', '14:00:10');
    const q = photo('q', '14:00:05');

    const result = computeReorderDates([p, q], ['x'], 'q', 'before');

    expect(result).toEqual([{ photoId: 'x', newDate: `${DAY}T14:00:06.000` }]);
  });

  it('dropping "after" a target uses that target as the "before" neighbor and the next one as anchor', () => {
    const p = photo('p', '14:00:10');
    const q = photo('q', '14:00:05');

    const result = computeReorderDates([p, q], ['x'], 'p', 'after');

    expect(result).toEqual([{ photoId: 'x', newDate: `${DAY}T14:00:06.000` }]);
  });

  it('dropped at the very start of the day (no newer/"before" neighbor) anchors off the after-neighbor only', () => {
    const q = photo('q', '14:00:05');

    const result = computeReorderDates([q], ['x'], 'q', 'before');

    expect(result).toEqual([{ photoId: 'x', newDate: `${DAY}T14:00:06.000` }]);
  });

  it('dropped at the very end of the day (no older/"after" neighbor) subtracts from the before-neighbor instead', () => {
    const p = photo('p', '14:00:10');

    const result = computeReorderDates([p], ['x'], 'p', 'after');

    expect(result).toEqual([{ photoId: 'x', newDate: `${DAY}T14:00:09.000` }]);
  });

  it('multiple moved items cascade seconds outward from the anchor, preserving their original relative order', () => {
    const p = photo('p', '14:00:20');
    const q = photo('q', '14:00:05');

    // x1 nearest p ("before" side), x3 nearest q ("after"/anchor side).
    const result = computeReorderDates([p, q], ['x1', 'x2', 'x3'], 'q', 'before');

    expect(result).toEqual([
      { photoId: 'x1', newDate: `${DAY}T14:00:08.000` },
      { photoId: 'x2', newDate: `${DAY}T14:00:07.000` },
      { photoId: 'x3', newDate: `${DAY}T14:00:06.000` },
    ]);
  });

  it('falls back to a millisecond cascade when the seconds-cascade would collide with/exceed the before-neighbor', () => {
    // Only 2 seconds of room (14:00:05 to 14:00:07) but 3 items need to fit.
    const p = photo('p', '14:00:07');
    const q = photo('q', '14:00:05');

    const result = computeReorderDates([p, q], ['x1', 'x2', 'x3'], 'q', 'before');

    expect(result).toEqual([
      { photoId: 'x1', newDate: `${DAY}T14:00:05.003` },
      { photoId: 'x2', newDate: `${DAY}T14:00:05.002` },
      { photoId: 'x3', newDate: `${DAY}T14:00:05.001` },
    ]);
  });

  it('multiple moved items with no after-neighbor cascade subtraction outward from the before-neighbor', () => {
    const p = photo('p', '14:00:20');

    const result = computeReorderDates([p], ['x1', 'x2', 'x3'], 'p', 'after');

    expect(result).toEqual([
      { photoId: 'x1', newDate: `${DAY}T14:00:19.000` },
      { photoId: 'x2', newDate: `${DAY}T14:00:18.000` },
      { photoId: 'x3', newDate: `${DAY}T14:00:17.000` },
    ]);
  });

  // Defensive clamp: an anchor already at the very end of the day must not spill the cascade into
  // the next calendar day.
  it('clamps results to the anchor\'s own calendar day', () => {
    const q = photo('q', '23:59:59.998');

    const result = computeReorderDates([q], ['x1', 'x2'], 'q', 'before');

    expect(result).toEqual([
      { photoId: 'x1', newDate: `${DAY}T23:59:59.999` },
      { photoId: 'x2', newDate: `${DAY}T23:59:59.999` },
    ]);
  });

  it('returns [] when the drop target is not found (also covers an empty day - nothing to drop onto)', () => {
    const p = photo('p', '14:00:10');

    expect(computeReorderDates([p], ['x'], 'missing', 'before')).toEqual([]);
    expect(computeReorderDates([], ['x'], 'missing', 'before')).toEqual([]);
  });
});
