import { describe, it, expect } from 'vitest';
import { removeFromFlatPhotos, removeFromPhotosByDay } from './photoListUpdates';

describe('removeFromFlatPhotos', () => {
  // Regression: garbage-ing a photo from DuplicateClusterModal (whose visible cluster is derived
  // live from `photos`, see PhotoGrid.jsx) used to close the modal and lose the library scroll
  // position, because the old refresh reset the whole grid to page 0. Removing in place must both
  // drop the garbage'd photo itself and scrub it out of every remaining photo's own `duplicates`
  // list, since that's what PhotoGrid.jsx's cluster grouping actually reads members from.
  it('removes the given ids and scrubs them from other photos\' duplicates lists', () => {
    const prev = [
      { id: 'primary', duplicates: [{ id: 'dup1' }, { id: 'dup2' }] },
      { id: 'dup1', duplicates: [{ id: 'primary' }, { id: 'dup2' }] },
      { id: 'dup2', duplicates: [{ id: 'primary' }, { id: 'dup1' }] },
    ];

    const next = removeFromFlatPhotos(prev, ['dup1']);

    expect(next.map(p => p.id)).toEqual(['primary', 'dup2']);
    expect(next.find(p => p.id === 'primary').duplicates.map(d => d.id)).toEqual(['dup2']);
    expect(next.find(p => p.id === 'dup2').duplicates.map(d => d.id)).toEqual(['primary']);
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
        { id: 'dup1', duplicates: [{ id: 'primary' }] },
      ],
      '2024-03-14': [{ id: 'unrelated' }],
    };

    const next = removeFromPhotosByDay(photosByDay, ['dup1']);

    expect(next['2024-03-15'].map(p => p.id)).toEqual(['primary']);
    expect(next['2024-03-15'][0].duplicates).toEqual([]);
    expect(next['2024-03-14']).toEqual([{ id: 'unrelated' }]);
  });

  it('returns an empty object for an empty input', () => {
    expect(removeFromPhotosByDay({}, ['x'])).toEqual({});
  });
});
