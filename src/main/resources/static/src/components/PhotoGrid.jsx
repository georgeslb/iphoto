import React, { useState, useEffect, useRef, useMemo } from 'react';
import { Check, Layers, Trash2 } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

const EXPANDED_CLUSTER_CARD_SIZE = 360;

export default function PhotoGrid({
  photos,
  hasMore,
  type,
  setPage,
  selectedIds,
  setSelectedIds,
  setSelectedPhoto,
  isLightboxOpen, // Accept the new prop
  handleMoveToGarbage
}) {
  const { messages } = useLocale(); // Use the useLocale hook
  const [hoveredVideoId, setHoveredVideoId] = useState(null);
  const [expandedGroups, setExpandedGroups] = useState(() => new Set());
  const observerTarget = useRef(null);
  const lastClickedIndexRef = useRef(null);

  const toggleGroupExpanded = (primaryId) => {
    setExpandedGroups(prev => {
      const next = new Set(prev);
      if (next.has(primaryId)) next.delete(primaryId); else next.add(primaryId);
      return next;
    });
  };

  const photoIndexById = useMemo(() => {
    const map = new Map();
    photos.forEach((p, i) => map.set(p.id, i));
    return map;
  }, [photos]);

  const selectRange = (toIndex) => {
    const fromIndex = lastClickedIndexRef.current;
    const start = Math.min(fromIndex, toIndex);
    const end = Math.max(fromIndex, toIndex);
    const rangeIds = photos.slice(start, end + 1).map(p => p.id);
    setSelectedIds(v => Array.from(new Set([...v, ...rangeIds])));
  };

  const handlePhotoClick = (p, e) => {
    const index = photoIndexById.get(p.id);
    if (e.shiftKey && lastClickedIndexRef.current !== null) {
      selectRange(index);
      return;
    }
    lastClickedIndexRef.current = index;
    if (selectedIds.length > 0) {
      // If we are already selecting items, a click on the card should toggle selection
      setSelectedIds(v => v.includes(p.id) ? v.filter(i => i !== p.id) : [...v, p.id]);
    } else {
      // Otherwise open lightbox
      setSelectedPhoto(p);
    }
  };

  const handleOverlayClick = (p, e) => {
    e.stopPropagation(); // Don't trigger the card's onClick
    const index = photoIndexById.get(p.id);
    if (e.shiftKey && lastClickedIndexRef.current !== null) {
      selectRange(index);
      return;
    }
    lastClickedIndexRef.current = index;
    setSelectedIds(v => v.includes(p.id) ? v.filter(i => i !== p.id) : [...v, p.id]);
  };

  // Duplicate photos come back from the API attached to each photo via `p.duplicates`
  // (empty/absent on endpoints that don't populate it yet). Cluster each photo with its
  // duplicates into one display item so they render together instead of as separate cards;
  // `consumedIds` prevents a duplicate from also being rendered as its own top-level item
  // when the flat `photos` list is later reached for it.
  const groups = {};
  const consumedIds = new Set();

  photos.forEach(p => {
    if (consumedIds.has(p.id)) return;
    consumedIds.add(p.id);

    const duplicateMembers = (p.duplicates || []).filter(d => !consumedIds.has(d.id));
    duplicateMembers.forEach(d => consumedIds.add(d.id));

    const key = p.groupDate || messages["otherGroup"];
    if (!groups[key]) groups[key] = [];
    groups[key].push({
      primary: p,
      members: duplicateMembers.length > 0 ? [p, ...duplicateMembers] : [p],
    });
  });

  const handleSelectGroup = (dayItems, e) => {
    e.stopPropagation();
    const groupIds = dayItems.flatMap(item => item.members.map(m => m.id));
    const allSelected = groupIds.length > 0 && groupIds.every(id => selectedIds.includes(id));

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

  const renderPhotoCard = (p, badge, size = 220) => {
    const aspectRatio = (p.width && p.height) ? (p.width / p.height) : 1;
    const isSelected = selectedIds.includes(p.id);
    return (
      <div
        key={p.id}
        className={`photo-card ${isSelected ? 'selected' : ''}`}
        style={{ flexBasis: `${aspectRatio * size}px`, height: `${size}px` }}
        onClick={(e) => handlePhotoClick(p, e)}
        onMouseEnter={() => p.contentType?.startsWith('video') && setHoveredVideoId(p.id)}
        onMouseLeave={() => setHoveredVideoId(null)}
      >
        <div
          className="select-overlay"
          onClick={(e) => handleOverlayClick(p, e)}
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
        {badge}
      </div>
    );
  };

  const renderDisplayItem = (item) => {
    if (item.members.length === 1) {
      return renderPhotoCard(item.primary);
    }

    const isExpanded = expandedGroups.has(item.primary.id);
    if (!isExpanded) {
      const badge = (
        <button
          type="button"
          className="duplicate-badge"
          title={messages["expandDuplicateGroup"]}
          onClick={(e) => { e.stopPropagation(); toggleGroupExpanded(item.primary.id); }}
        >
          <Layers size={12} /> {messages["similarPhotosBadge"](item.members.length)}
        </button>
      );
      return renderPhotoCard(item.primary, badge);
    }

    return (
      <div key={item.primary.id} className="duplicate-cluster">
        <div className="duplicate-cluster-header">
          <span className="duplicate-cluster-label">
            <Layers size={12} /> {messages["similarPhotosBadge"](item.members.length)}
          </span>
          <button
            type="button"
            className="duplicate-collapse-btn"
            title={messages["collapseDuplicateGroup"]}
            onClick={(e) => { e.stopPropagation(); toggleGroupExpanded(item.primary.id); }}
          >
            {messages["collapseDuplicateGroup"]}
          </button>
        </div>
        <div className="duplicate-cluster-row">
          {item.members.map(m => renderPhotoCard(m, handleMoveToGarbage && (
            <button
              type="button"
              className="duplicate-remove-btn"
              title={messages["moveToGarbage"]}
              onClick={(e) => { e.stopPropagation(); handleMoveToGarbage([m.id]); }}
            >
              <Trash2 size={14} />
            </button>
          ), EXPANDED_CLUSTER_CARD_SIZE))}
        </div>
      </div>
    );
  };

  return (
    <div className="content-body">
      {Object.keys(groups).map(day => {
        const dayItems = groups[day];
        const groupIds = dayItems.flatMap(item => item.members.map(m => m.id));
        const allSelected = groupIds.length > 0 && groupIds.every(id => selectedIds.includes(id));

        return (
          <div key={day} className="day-group">
            <div className="day-header" style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <h3>{day}</h3>
              <div
                className={`check-circle day-check ${allSelected ? 'checked' : ''}`}
                style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', width: '20px', height: '20px', borderRadius: '50%', border: '2px solid #ccc' }}
                onClick={(e) => handleSelectGroup(dayItems, e)}
              >
                {allSelected && <Check size={14} color="white" />}
              </div>
            </div>
            <div className="photo-grid">
              {dayItems.map(item => renderDisplayItem(item))}
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