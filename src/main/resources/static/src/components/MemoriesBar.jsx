import React, { useRef, useState, useEffect } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx';
import { useMemories } from '../hooks/useMemories';

const AUTO_SWITCH_INTERVAL_MS = 3000;

// One block: cycles automatically through its own memory.photos (up to
// IPhotoProperties.Memories.photosPerBlock on the backend, fewer if that day has less) instead of
// showing a single static photo. Its own component (not inline in the map below) so each block's
// rotation timer/state is independent and cleaned up correctly per block.
function MemoryItem({ memory, label, onPhotoClick }) {
  const [index, setIndex] = useState(0);
  const photos = memory.photos;

  useEffect(() => {
    if (photos.length < 2) return undefined;
    const interval = setInterval(() => {
      setIndex(i => (i + 1) % photos.length);
    }, AUTO_SWITCH_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [photos.length]);

  const current = photos[index];

  return (
    <div className="memories-strip-item" onClick={() => onPhotoClick(current)}>
      {/* key forces a remount on every switch, restarting the CSS fade-in animation */}
      <img key={current.id} src={current.url} alt={label}/>
      <span>{label}</span>
    </div>
  );
}

// Library-only horizontal strip: one block per configured "N months/years ago" offset (see
// IPhotoProperties.Memories.monthsAgo on the backend) that actually has a photo that day. Mirrors
// PersonStrip.jsx's arrow-scrollable layout.
export default function MemoriesBar({ onPhotoClick }) {
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
            onPhotoClick={onPhotoClick}
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
