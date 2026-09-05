// Small, pure helpers for removing photo ids from whichever shape the current view's photo state
// is in - a flat array (usePhotoData.js, Garbage/Person) or a day-bucketed map
// (hooks/useDayIndexedPhotos.js, Library/Album/Space). Used by App.jsx's removePhotosLocally,
// which handleMoveToGarbage (hooks/usePhotoActions.js) calls instead of a full grid refetch - see
// that hook's comment for why a full refetch is the wrong move here.

// Drops `ids` from a flat photo list and scrubs them out of every remaining photo's own
// `duplicates` array too, since that's what PhotoGrid.jsx's cluster grouping reads members from,
// not a re-derivation off the top-level list.
export function removeFromFlatPhotos(photos, ids) {
  return photos
    .filter(p => !ids.includes(p.id))
    .map(p => (
      p.duplicates && p.duplicates.some(d => ids.includes(d.id))
        ? { ...p, duplicates: p.duplicates.filter(d => !ids.includes(d.id)) }
        : p
    ));
}

// Same removal, applied independently within every day bucket of the day-indexed shape.
export function removeFromPhotosByDay(photosByDay, ids) {
  const next = {};
  for (const [day, dayPhotos] of Object.entries(photosByDay)) {
    next[day] = removeFromFlatPhotos(dayPhotos, ids);
  }
  return next;
}
