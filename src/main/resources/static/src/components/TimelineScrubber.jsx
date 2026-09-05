import React, { useRef, useState } from 'react';

// Right-edge vertical scrubber over a day-indexed PhotoGrid (Library/Album/Space/Garbage/Person) -
// click or drag-and-release to jump straight to a day's section, skipping the scroll (and so the
// fetch) of everything in between. Position is index-based (index into `days`, already sorted
// newest-first) rather than measured pixel height, since a day's real rendered height depends on
// how its photos wrap and isn't cheap to know up front for every day.
//
// Dragging only updates the hover preview live; the actual seek (PhotoGrid.seekToDay - an instant
// scrollIntoView + eager loadDayPage) fires once on release, not on every intermediate position
// the pointer passes over - a continuous per-pixel seek during a slow drag would end up loading
// every day swept over, defeating the point.
export default function TimelineScrubber({ days, onSeek }) {
  const trackRef = useRef(null);
  const draggingRef = useRef(false);
  const [hover, setHover] = useState(null); // { fraction, day } | null

  if (!days || days.length < 2) return null;

  const posFromEvent = (e) => {
    const rect = trackRef.current.getBoundingClientRect();
    const fraction = Math.min(1, Math.max(0, (e.clientY - rect.top) / rect.height));
    const index = Math.round(fraction * (days.length - 1));
    return { fraction, day: days[index].day };
  };

  const handlePointerDown = (e) => {
    trackRef.current.setPointerCapture(e.pointerId);
    draggingRef.current = true;
    setHover(posFromEvent(e));
  };

  const handlePointerMove = (e) => {
    setHover(posFromEvent(e));
  };

  const handlePointerUp = (e) => {
    if (!draggingRef.current) return;
    draggingRef.current = false;
    trackRef.current.releasePointerCapture(e.pointerId);
    if (hover) onSeek(hover.day);
  };

  // One marker per first occurrence of a new (older) year - `days` is sorted newest-first, so a
  // year's FIRST appearance in the array is its most recent day.
  const yearMarkers = [];
  let lastYear = null;
  days.forEach((d, i) => {
    const year = d.day.slice(0, 4);
    if (year !== lastYear) {
      yearMarkers.push({ year, fraction: i / (days.length - 1) });
      lastYear = year;
    }
  });

  return (
    <div
      className="timeline-scrubber"
      ref={trackRef}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
      onPointerLeave={() => { if (!draggingRef.current) setHover(null); }}
    >
      <div className="timeline-scrubber-track"/>
      {yearMarkers.map(m => (
        <div key={m.year} className="timeline-scrubber-year" style={{ top: `${m.fraction * 100}%` }}>
          {m.year}
        </div>
      ))}
      {hover && (
        <div className="timeline-scrubber-tooltip" style={{ top: `${hover.fraction * 100}%` }}>
          {hover.day}
        </div>
      )}
    </div>
  );
}
