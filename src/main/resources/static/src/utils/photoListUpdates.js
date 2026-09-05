// Small, pure helpers for removing photo ids from whichever shape the current view's photo state
// is in - a flat array (usePhotoData.js, Garbage/Person) or a day-bucketed map
// (hooks/useDayIndexedPhotos.js, Library/Album/Space). Used by App.jsx's removePhotosLocally,
// which handleMoveToGarbage (hooks/usePhotoActions.js) calls instead of a full grid refetch - see
// that hook's comment for why a full refetch is the wrong move here.

// Drops `ids` from a flat photo list and scrubs them out of every remaining photo's own
// `duplicates` array too, since that's what PhotoGrid.jsx's cluster grouping reads members from,
// not a re-derivation off the top-level list.
//
// A cluster's non-primary members (see PhotoService.buildDedupedPage) exist ONLY nested inside
// their primary's own `duplicates` array - they're deliberately excluded from ever appearing as
// their own top-level entries. So removing the PRIMARY itself (e.g. garbage-ing the suggested
// keep from DuplicateClusterModal) can't just be a plain filter() - that would silently delete
// every surviving member along with it, since nothing else in the list references them. Instead,
// promote the first survivor to take the removed primary's place, with the rest becoming its own
// `duplicates` - the cluster (now anchored to a different primary) stays visible and intact.
export function removeFromFlatPhotos(photos, ids) {
  const result = [];
  for (const p of photos) {
    if (ids.includes(p.id)) {
      const survivors = (p.duplicates || []).filter(d => !ids.includes(d.id));
      if (survivors.length > 0) {
        const [promoted, ...rest] = survivors;
        result.push({ ...promoted, duplicates: rest });
      }
      continue;
    }
    result.push(
      p.duplicates && p.duplicates.some(d => ids.includes(d.id))
        ? { ...p, duplicates: p.duplicates.filter(d => !ids.includes(d.id)) }
        : p
    );
  }
  return result;
}

// Same removal, applied independently within every day bucket of the day-indexed shape.
export function removeFromPhotosByDay(photosByDay, ids) {
  const next = {};
  for (const [day, dayPhotos] of Object.entries(photosByDay)) {
    next[day] = removeFromFlatPhotos(dayPhotos, ids);
  }
  return next;
}
