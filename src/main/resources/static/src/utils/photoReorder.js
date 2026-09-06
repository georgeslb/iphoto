// Pure date-math for drag-to-reorder-within-a-day (PhotoGrid.jsx's drag handle). No React/DOM -
// same "extract the pure list-manipulation logic out of the component" pattern as
// photoListUpdates.js, so the cascade math can be unit-tested directly against exhaustive edge
// cases rather than through a real drag-and-drop gesture (jsdom's native HTML5 DnD support is
// poor, so PhotoGrid.jsx itself is deliberately NOT drag-simulation-tested - see its own comments).
//
// The grid displays a day newest-first (dateTaken DESC). Dropping photo(s) between two neighbors
// P (ends up displayed just BEFORE the moved item(s), i.e. more recent) and Q (ends up displayed
// just AFTER, i.e. older) needs the moved item(s) to land strictly between P and Q chronologically.
// Confirmed anchor (via a concrete worked example): the moved item's new dateTaken is anchored off
// Q (the *older*, "after" neighbor) plus 1 second - NOT off P. E.g. P=14:00:10, Q=14:00:05, drop
// between them -> new dateTaken = 14:00:06 (Q + 1s), landing correctly between the two.

const SECOND_MS = 1000;

function parseTime(photo) {
  return new Date(photo.fullDateTime).getTime();
}

// Formats an epoch-ms value back into an ISO_LOCAL_DATE_TIME-shaped string
// ("yyyy-MM-ddTHH:mm:ss.SSS") using LOCAL Date getters only - never toISOString(), which is
// UTC-based and would shift the naive, no-timezone local time this app treats every dateTaken/
// fullDateTime string as everywhere else (confirmed: the datetime-local input's value is sent to
// the backend completely unmodified, no timezone conversion happens anywhere in this app).
function formatLocal(timeMs) {
  const d = new Date(timeMs);
  const pad = (n, len = 2) => String(n).padStart(len, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
      `T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
}

function startOfDay(timeMs) {
  const d = new Date(timeMs);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

function endOfDay(timeMs) {
  const d = new Date(timeMs);
  d.setHours(23, 59, 59, 999);
  return d.getTime();
}

// Clamps every computed time into the anchor's own calendar day - a purely defensive guard against
// a cascade spilling into a different day (e.g. an anchor already at 23:59:59.999.) Ties on
// clamping are accepted as-is in this rare edge case, not resolved further.
function clampToDay(timeMs, anchorTimeMs) {
  return Math.min(Math.max(timeMs, startOfDay(anchorTimeMs)), endOfDay(anchorTimeMs));
}

// dayPhotosDesc: the day's OTHER primaries (moved ids already excluded), [{id, fullDateTime}],
// in current display order (newest-first/DESC).
// movedIds: ids being dragged, in their ORIGINAL relative display order (X1 nearest the "before"
// side of the drop point, XN nearest the "after"/anchor side).
// targetId/position ('before'|'after'): the drop target and which half of it was dropped on.
// Returns [{photoId, newDate}], one per movedIds entry (same order), or [] if the drop is a no-op
// (target not found, or the day has nothing left to anchor off once the moved ids are excluded).
export function computeReorderDates(dayPhotosDesc, movedIds, targetId, position) {
  const targetIndex = dayPhotosDesc.findIndex(p => p.id === targetId);
  if (targetIndex === -1) return [];

  const before = position === 'before'
      ? dayPhotosDesc[targetIndex - 1]
      : dayPhotosDesc[targetIndex];
  const after = position === 'before'
      ? dayPhotosDesc[targetIndex]
      : dayPhotosDesc[targetIndex + 1];

  const n = movedIds.length;
  const times = new Array(n);

  if (after) {
    // Anchor = after (Q): cascade outward from it, XN (nearest Q) = Q+1s, ..., X1 = Q+n*1s.
    const anchor = parseTime(after);
    for (let k = 1; k <= n; k++) {
      times[n - k] = anchor + k * SECOND_MS;
    }
    // Tight gap: the seconds-cascade would collide with/exceed the "before" neighbor - redo with
    // a millisecond cascade instead (generalizes the confirmed single-item "+1ms" fallback to N
    // items, preserving their relative order the same way the seconds-cascade does).
    if (before && times[0] >= parseTime(before)) {
      for (let k = 1; k <= n; k++) {
        times[n - k] = anchor + k;
      }
    }
  } else if (before) {
    // No "after" neighbor (dropped at the day's oldest/last position) - symmetric subtraction off
    // "before" (P), unbounded below since nothing is further out.
    const anchor = parseTime(before);
    for (let k = 1; k <= n; k++) {
      times[k - 1] = anchor - k * SECOND_MS;
    }
  } else {
    // Neither neighbor exists - the day has nothing left to anchor off once the moved ids are
    // excluded. Shouldn't be reachable from the UI (there must be at least one other card to drop
    // onto), but guard anyway.
    return [];
  }

  const anchorTimeMs = after ? parseTime(after) : parseTime(before);
  return movedIds.map((photoId, i) => ({
    photoId,
    newDate: formatLocal(clampToDay(times[i], anchorTimeMs)),
  }));
}
