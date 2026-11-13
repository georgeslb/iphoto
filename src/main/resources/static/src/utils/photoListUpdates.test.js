import { describe, it, expect } from 'vitest';
import { removeFromFlatPhotos, removeFromPhotosByDay, reorderInFlatPhotos, reorderInPhotosByDay } from './photoListUpdates';

// Real backend shape (see PhotoService.buildDedupedPage/PhotoMapper): only a cluster's PRIMARY
// ever appears as its own top-level entry - the other members exist ONLY nested inside the
// primary's own `duplicates` array, never as separate top-level entries.
describe('removeFromFlatPhotos', () => {
  // Regression: garbage-ing a photo from DuplicateClusterModal (whose visible cluster is derived
  // live from `photos`, see PhotoGrid.jsx) used to close the modal and lose the library scroll
  // position, because the old refresh reset the whole grid to page 0. Removing in place must both
  // drop the garbage'd photo itself and scrub it out of the primary's own `duplicates` list.
  it('removes a non-primary duplicate id and scrubs it from the primary\'s duplicates list', () => {
    const prev = [
      { id: 'primary', duplicates: [{ id: 'dup1' }, { id: 'dup2' }] },
      { id: 'other' },
    ];

    const next = removeFromFlatPhotos(prev, ['dup1']);

    expect(next.map(p => p.id)).toEqual(['primary', 'other']);
    expect(next.find(p => p.id === 'primary').duplicates).toEqual([{ id: 'dup2' }]);
  });

  // The actual bug this fixes: garbage-ing the SUGGESTED KEEP (the primary itself, not one of its
  // duplicate members) used to silently drop its surviving members entirely - they only ever
  // existed nested inside the removed primary's own `duplicates` array, never as their own
  // top-level entries, so a plain filter() lost them for good until the next full refetch (and
  // closed DuplicateClusterModal, since the item it was anchored to had vanished from `groups`).
  it('promotes a surviving duplicate to take the removed primary\'s place, keeping the rest as its own duplicates', () => {
    const prev = [
      { id: 'primary', title: 'keeper', duplicates: [{ id: 'dup1', title: 'a' }, { id: 'dup2', title: 'b' }] },
      { id: 'other' },
    ];

    const next = removeFromFlatPhotos(prev, ['primary']);

    expect(next.map(p => p.id)).toEqual(['dup1', 'other']);
    expect(next.find(p => p.id === 'dup1').duplicates).toEqual([{ id: 'dup2', title: 'b' }]);
  });

  it('drops the cluster entirely when the primary and all its remaining duplicates are removed together', () => {
    const prev = [
      { id: 'primary', duplicates: [{ id: 'dup1' }] },
      { id: 'other' },
    ];

    const next = removeFromFlatPhotos(prev, ['primary', 'dup1']);

    expect(next.map(p => p.id)).toEqual(['other']);
  });

  it('leaves photos without any affected duplicates untouched', () => {
    const untouched = { id: 'other', duplicates: [{ id: 'unrelated' }] };
    const prev = [untouched, { id: 'gone' }];

    const next = removeFromFlatPhotos(prev, ['gone']);

    expect(next).toEqual([untouched]);
  });

  it('handles photos with no duplicates field at all', () => {
    const prev = [{ id: 'a' }, { id: 'b' }];
    expect(removeFromFlatPhotos(prev, ['b'])).toEqual([{ id: 'a' }]);
  });
});

describe('removeFromPhotosByDay', () => {
  it('applies the same removal independently within every day bucket', () => {
    const photosByDay = {
      '2024-03-15': [
        { id: 'primary', duplicates: [{ id: 'dup1' }] },
        { id: 'unrelated' },
      ],
      '2024-03-14': [{ id: 'other' }],
    };

    const next = removeFromPhotosByDay(photosByDay, ['dup1']);

    expect(next['2024-03-15'].map(p => p.id)).toEqual(['primary', 'unrelated']);
    expect(next['2024-03-15'][0].duplicates).toEqual([]);
    expect(next['2024-03-14']).toEqual([{ id: 'other' }]);
  });

  it('promotes a surviving duplicate within its own day bucket when a primary is removed', () => {
    const photosByDay = {
      '2024-03-15': [{ id: 'primary', duplicates: [{ id: 'dup1' }] }],
    };

    const next = removeFromPhotosByDay(photosByDay, ['primary']);

    expect(next['2024-03-15'].map(p => p.id)).toEqual(['dup1']);
  });

  it('returns an empty object for an empty input', () => {
    expect(removeFromPhotosByDay({}, ['x'])).toEqual({});
  });
});

describe('reorderInFlatPhotos', () => {
  it('updates dates and re-sorts descending by fullDateTime', () => {
    const prev = [
      { id: 'p1', fullDateTime: '2024-03-15T14:00:10.000' },
      { id: 'p2', fullDateTime: '2024-03-15T14:00:05.000' },
    ];
    // Move p2 before p1 by giving it a newer date
    const updates = [{ photoId: 'p2', newDate: '2024-03-15T14:00:11.000' }];
    const next = reorderInFlatPhotos(prev, updates);

    expect(next.map(p => p.id)).toEqual(['p2', 'p1']);
    expect(next[0].fullDateTime).toBe('2024-03-15T14:00:11.000');
  });

  it('returns original array when updates are empty', () => {
    const prev = [{ id: 'p1', fullDateTime: '2024-03-15T14:00:10.000' }];
    expect(reorderInFlatPhotos(prev, [])).toBe(prev);
  });
});

describe('reorderInPhotosByDay', () => {
  it('reorders only within the day that has updates, preserving other days untouched', () => {
    const photosByDay = {
      '2024-03-15': [
        { id: 'p1', fullDateTime: '2024-03-15T14:00:10.000' },
        { id: 'p2', fullDateTime: '2024-03-15T14:00:05.000' },
      ],
      '2024-03-14': [
        { id: 'p3', fullDateTime: '2024-03-14T10:00:00.000' },
      ],
    };

    const updates = [{ photoId: 'p2', newDate: '2024-03-15T14:00:12.000' }];
    const next = reorderInPhotosByDay(photosByDay, updates);

    expect(next['2024-03-15'].map(p => p.id)).toEqual(['p2', 'p1']);
    expect(next['2024-03-15'][0].fullDateTime).toBe('2024-03-15T14:00:12.000');
    expect(next['2024-03-14']).toBe(photosByDay['2024-03-14']);
  });

  it('returns original object when updates are empty or null', () => {
    const photosByDay = { '2024-03-15': [] };
    expect(reorderInPhotosByDay(photosByDay, [])).toBe(photosByDay);
    expect(reorderInPhotosByDay(photosByDay, null)).toBe(photosByDay);
  });
});
