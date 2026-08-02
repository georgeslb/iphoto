import React, { useState, useEffect, useRef } from 'react';
import { Check } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

export default function PhotoGrid({
  photos,
  hasMore,
  setPage,
  selectedIds,
  setSelectedIds,
  setSelectedPhoto,
  isLightboxOpen // Accept the new prop
}) {
  const { messages } = useLocale(); // Use the useLocale hook
  const [hoveredVideoId, setHoveredVideoId] = useState(null);
  const observerTarget = useRef(null);

  const groups = {};
  photos.forEach(p => {
    const key = p.groupDate || messages["otherGroup"];
    if (!groups[key]) groups[key] = [];
    groups[key].push(p);
  });

  const handleSelectGroup = (groupPhotos, e) => {
    e.stopPropagation();
    const groupIds = groupPhotos.map(p => p.id);
    const allSelected = groupIds.every(id => selectedIds.includes(id));

    if (allSelected) {
      // Deselect all in group
      setSelectedIds(prev => prev.filter(id => !groupIds.includes(id)));
    } else {
      // Select all in group
      setSelectedIds(prev => {
        const newIds = new Set([...prev, ...groupIds]);
        return Array.from(newIds);
      });
    }
  };

  useEffect(() => {
    const observer = new IntersectionObserver(
      entries => {
        // Only load more photos if the lightbox is NOT open
        if (entries[0].isIntersecting && hasMore && !isLightboxOpen) {
          setPage(p => p + 1);
        }
      },
      { threshold: 0.1, rootMargin: '0px 0px 400px 0px' } // Pre-load 400px before reaching the bottom
    );

    const currentTarget = observerTarget.current;
    if (currentTarget) {
      observer.observe(currentTarget);
    }

    return () => {
      if (currentTarget) {
        observer.unobserve(currentTarget);
      }
    };
  }, [hasMore, setPage, isLightboxOpen]); // Add isLightboxOpen to dependencies

  return (
    <div className="content-body">
      {Object.keys(groups).map(day => {
        const groupPhotos = groups[day];
        const groupIds = groupPhotos.map(p => p.id);
        const allSelected = groupPhotos.length > 0 && groupIds.every(id => selectedIds.includes(id));

        return (
          <div key={day} className="day-group">
            <div className="day-header" style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <h3>{day}</h3>
              <div
                className={`check-circle day-check ${allSelected ? 'checked' : ''}`}
                style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', width: '20px', height: '20px', borderRadius: '50%', border: '2px solid #ccc' }}
                onClick={(e) => handleSelectGroup(groupPhotos, e)}
              >
                {allSelected && <Check size={14} color="white" />}
              </div>
            </div>
            <div className="photo-grid">
              {groupPhotos.map(p => {
                const aspectRatio = (p.width && p.height) ? (p.width / p.height) : 1;
                const isSelected = selectedIds.includes(p.id);
                return (
                  <div
                    key={p.id}
                    className={`photo-card ${isSelected ? 'selected' : ''}`}
                    style={{ flexBasis: `${aspectRatio * 220}px` }}
                    onClick={() => {
                      if (selectedIds.length > 0) {
                         // If we are already selecting items, a click on the card should toggle selection
                         setSelectedIds(v => v.includes(p.id) ? v.filter(i => i !== p.id) : [...v, p.id]);
                      } else {
                         // Otherwise open lightbox
                         setSelectedPhoto(p);
                      }
                    }}
                    onMouseEnter={() => p.contentType?.startsWith('video') && setHoveredVideoId(p.id)}
                    onMouseLeave={() => setHoveredVideoId(null)}
                  >
                    <div
                      className="select-overlay"
                      onClick={(e) => {
                        e.stopPropagation(); // Don't trigger the card's onClick
                        setSelectedIds(v => v.includes(p.id) ? v.filter(i => i !== p.id) : [...v, p.id]);
                      }}
                    >
                      <div className={`check-circle ${isSelected ? 'checked' : ''}`}>
                        {isSelected && <Check size={14} />}
                      </div>
                    </div>

                    {/* Conditionally render video or image based on isLightboxOpen */}
                    {isLightboxOpen ? (
                      // Render a placeholder or nothing if lightbox is open to prevent thumbnail loading
                      <div style={{ width: '100%', height: '100%', background: '#e0e0e5' }} />
                    ) : (
                      hoveredVideoId === p.id ?
                        <video src={`/api/photos/${p.id}/stream#t=0.5`} autoPlay muted loop playsInline className="card-video" /> :
                        <img src={p.url} loading="lazy" />
                    )}
                    {!isLightboxOpen && p.contentType?.startsWith('video') && hoveredVideoId !== p.id && <div className="video-badge">▶</div>}
                  </div>
                )
              })}
            </div>
          </div>
        )
      })}

      {/* Intersection Observer Target */}
      <div
        ref={observerTarget}
        style={{ height: '20px', margin: '20px 0', display: 'flex', justifyContent: 'center', alignItems: 'center' }}
      >
        {hasMore && <span style={{ color: '#888' }}>{messages["loadingMorePhotos"]}</span>}
      </div>
    </div>
  );
}