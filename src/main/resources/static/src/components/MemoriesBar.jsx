import React, { useRef, useState, useEffect } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx';
import { useMemories } from '../hooks/useMemories';

const AUTO_SWITCH_INTERVAL_MS = 3000;

// A memory's photos can each land on a DIFFERENT actual day when its offset carries tolerance
// (see MemoryOffset/IPhotoProperties.Memories.offsets - up to photosPerBlock photos are drawn from
// anywhere in the whole window, not necessarily the same day), so there's no single well-defined
// day for a whole block - only for whichever photo is currently shown. fullDateTime is always a
// real ISO_LOCAL_DATE_TIME string here (Memories photos are only ever found via a groupedDay
// query, so dateTaken - and so fullDateTime - is never the "Unknown Date/Time" fallback), and its
// first 10 characters are guaranteed to be the plain yyyy-MM-dd day, matching dayIndex.days'/
// data-day-group's own format exactly - no need for a dedicated backend field just for this.
function dayOf(photo) {
  return photo.fullDateTime.slice(0, 10);
}

// One block: cycles automatically through its own memory.photos (up to
// IPhotoProperties.Memories.photosPerBlock on the backend, fewer if that day has less) instead of
// showing a single static photo. Its own component (not inline in the map below) so each block's
// rotation timer/state is independent and cleaned up correctly per block.
function MemoryItem({ memory, label, onSeekToDay, isLightboxOpen }) {
  const [index, setIndex] = useState(0);
  const photos = memory.photos;

  // Paused while the Lightbox is open: this bar sits underneath the fullscreen overlay (still
  // mounted, just visually covered), so left running it kept silently fetching a new, unrelated
  // photo's thumbnail every tick - the auto-cycle has no reason to advance (and fetch) while
  // nobody can see it.
  useEffect(() => {
    if (photos.length < 2 || isLightboxOpen) return undefined;
    const interval = setInterval(() => {
      setIndex(i => (i + 1) % photos.length);
    }, AUTO_SWITCH_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [photos.length, isLightboxOpen]);

  const current = photos[index];

  return (
    <div className="memories-strip-item" onClick={() => onSeekToDay(dayOf(current))}>
      {/* key forces a remount on every switch, restarting the CSS fade-in animation */}
      <img key={current.id} src={current.url} alt={label}/>
      <span>{label}</span>
    </div>
  );
}

// Library-only horizontal strip: one block per configured "N months/years ago" offset (see
// IPhotoProperties.Memories.monthsAgo on the backend) that actually has a photo that day. Mirrors
// PersonStrip.jsx's arrow-scrollable layout.
export default function MemoriesBar({ onSeekToDay, isLightboxOpen }) {
  const { messages } = useLocale();
  const { memories, loading } = useMemories();
  const trackRef = useRef(null);

  const scrollByAmount = (amount) => {
    trackRef.current?.scrollBy({ left: amount, behavior: 'smooth' });
  };

  const labelFor = (monthsAgo) => (
    monthsAgo % 12 === 0
      ? messages["memoryYearsAgo"](monthsAgo / 12)
      : messages["memoryMonthsAgo"](monthsAgo)
  );

  if (loading || memories.length === 0) return null;

  return (
    <div className="memories-strip">
      <button
        type="button"
        className="memories-strip-arrow"
        onClick={() => scrollByAmount(-300)}
        aria-label={messages["scrollMemoriesLeft"]}
      >
        <ChevronLeft size={20}/>
      </button>
      <div className="memories-strip-track" ref={trackRef}>
        {memories.map(memory => (
          <MemoryItem
            key={memory.monthsAgo}
            memory={memory}
            label={labelFor(memory.monthsAgo)}
            onSeekToDay={onSeekToDay}
            isLightboxOpen={isLightboxOpen}
          />
        ))}
      </div>
      <button
        type="button"
        className="memories-strip-arrow"
        onClick={() => scrollByAmount(300)}
        aria-label={messages["scrollMemoriesRight"]}
      >
        <ChevronRight size={20}/>
      </button>
    </div>
  );
}
