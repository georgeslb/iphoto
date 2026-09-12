import React, { useState, useRef, useCallback } from 'react';
import { ChevronDown, ChevronUp, ChevronLeft, ChevronRight } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx';
import { useRecentPhotos } from '../hooks/useRecentPhotos';

// How close to the right edge (px) the track has to be scrolled before the next page loads.
const SCROLL_LOAD_THRESHOLD_PX = 400;

// Collapsed by default and fetches nothing until first expanded (see useRecentPhotos) - a flat,
// non-day-grouped, most-recently-*uploaded*-first strip, deliberately separate from the
// day-indexed grid below it (no timeline/day headers here at all). Shown on Library (under
// MemoriesBar) and at the top of each Space detail page - not Album/Garbage/Person.
export default function RecentlyAddedSection({ baseUrl, onPhotoClick, isLightboxOpen }) {
  const { messages } = useLocale();
  const [expanded, setExpanded] = useState(false);
  const { photos, loading, hasLoaded, loadMore } = useRecentPhotos(baseUrl);
  const trackRef = useRef(null);

  const toggle = () => {
    const next = !expanded;
    setExpanded(next);
    if (next && !hasLoaded) loadMore();
  };

  // Horizontal-scroll pagination: load the next page once the track is scrolled near its end,
  // rather than a "load more" button - matches the "fetch next page on horizontal scroll" ask.
  const handleScroll = useCallback((e) => {
    const el = e.target;
    if (el.scrollWidth - el.scrollLeft - el.clientWidth < SCROLL_LOAD_THRESHOLD_PX) {
      loadMore();
    }
  }, [loadMore]);

  const scrollByAmount = (amount) => {
    trackRef.current?.scrollBy({ left: amount, behavior: 'smooth' });
  };

  return (
    <div className="recent-assets">
      <button type="button" className="recent-assets-header" onClick={toggle} aria-expanded={expanded}>
        <span>{messages["recentlyAdded"]}</span>
        {expanded ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
      </button>
      {expanded && (
        <div className="recent-assets-body">
          <button
              type="button"
              className="memories-strip-arrow"
              onClick={() => scrollByAmount(-300)}
              aria-label={messages["scrollMemoriesLeft"]}
          >
            <ChevronLeft size={20} />
          </button>
          <div className="recent-assets-track" ref={trackRef} onScroll={handleScroll}>
            {photos.map(p => (
              <div key={p.id} className="recent-assets-item" onClick={() => onPhotoClick(p)}>
                {!isLightboxOpen && <img src={p.url} loading="lazy" alt="" />}
                {p.contentType?.startsWith('video') && <div className="video-badge">▶</div>}
              </div>
            ))}
            {loading && <div className="recent-assets-status">{messages["loadingMorePhotos"]}</div>}
            {hasLoaded && photos.length === 0 && !loading && (
              <div className="recent-assets-status">{messages["noRecentAssets"]}</div>
            )}
          </div>
          <button
              type="button"
              className="memories-strip-arrow"
              onClick={() => scrollByAmount(300)}
              aria-label={messages["scrollMemoriesRight"]}
          >
            <ChevronRight size={20} />
          </button>
        </div>
      )}
    </div>
  );
}
