import React, { useState, useEffect, useLayoutEffect, useRef, useMemo } from 'react';
import { Check, Layers, Unlink2 } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale
import DuplicateClusterModal from './DuplicateClusterModal';

export default function PhotoGrid({
  photos,
  hasMore,
  type,
  setPage,
  selectedIds,
  setSelectedIds,
  setSelectedPhoto,
  isLightboxOpen, // Accept the new prop
  handleMoveToGarbage,
  handleUnlinkDuplicate, // Only passed where duplicates are populated (Library) — DuplicateClusterModal's unlink icon
  handleUnlinkPerson, // Only passed from PersonDetail.jsx — unlinks this person from a photo
  // The three below are only ever passed by the Library route (App.jsx), after a
  // LibraryTimeline.jsx jump — they enable back-scrolling above the jumped-to point. Left
  // undefined everywhere else (Album/Space/Person/Garbage/duplicates), which silently disables
  // the top sentinel/loader below entirely — no behavior change for those views.
  hasNewer,
  onLoadNewer,
  mainContentRef
}) {
  const { messages } = useLocale(); // Use the useLocale hook
  const [hoveredVideoId, setHoveredVideoId] = useState(null);
  // Which duplicate cluster's "×N similar" popup is open (DuplicateClusterModal.jsx, replacing
  // the old expand-in-place row) — the PRIMARY PHOTO'S ID, not the item object itself, so the
  // modal always re-derives its member list from the current `groups` below (computed below,
  // after this) rather than a stale snapshot. That matters because moving a member to garbage
  // from inside the modal changes `photos`/`groups` on the next render, and the modal should
  // reflect that (member disappears) rather than keep showing an already-garbage-collected photo
  // until manually closed.
  const [openDuplicatePrimaryId, setOpenDuplicatePrimaryId] = useState(null);
  const observerTarget = useRef(null);
  const topObserverTarget = useRef(null);
  const lastClickedIndexRef = useRef(null);
  const loadingNewerRef = useRef(false);
  // Set right before a loadNewer fetch starts, holding the scroll container's height at that
  // moment; the useLayoutEffect below reads it once the newly-prepended photos have actually
  // rendered, to shift scrollTop by exactly how much taller the content just got — otherwise
  // prepending content above the current viewport would visually yank whatever the user was
  // looking at downward by that same amount.
  const pendingPrependRef = useRef(null);

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

  // Re-derived every render from the live `groups` above (see openDuplicatePrimaryId's own
  // comment for why) — null once the id no longer matches anything, which also naturally closes
  // the modal if its entire cluster is ever fully cleared out from under it.
  const openDuplicateItem = openDuplicatePrimaryId
    ? Object.values(groups).flat().find(it => it.primary.id === openDuplicatePrimaryId) || null
    : null;

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

  // Back-scrolling: loads NEWER photos when the top sentinel comes into view. Only wired up at
  // all when the caller passed onLoadNewer (Library route, post-jump) — everywhere else hasNewer/
  // onLoadNewer are undefined and this effect's observer target never renders, so it's a no-op.
  const handleLoadNewer = () => {
    if (!onLoadNewer || !hasNewer || loadingNewerRef.current) return;
    loadingNewerRef.current = true;
    if (mainContentRef?.current) {
      pendingPrependRef.current = { scrollHeightBefore: mainContentRef.current.scrollHeight };
    }
    Promise.resolve(onLoadNewer()).finally(() => {
      loadingNewerRef.current = false;
    });
  };

  useEffect(() => {
    if (!onLoadNewer) return undefined;

    const observer = new IntersectionObserver(
      entries => {
        if (entries[0].isIntersecting && !isLightboxOpen) {
          handleLoadNewer();
        }
      },
      { threshold: 0.1, rootMargin: '400px 0px 0px 0px' } // Pre-load 400px before reaching the top
    );

    const currentTarget = topObserverTarget.current;
    if (currentTarget) {
      observer.observe(currentTarget);
    }
    return () => {
      if (currentTarget) {
        observer.unobserve(currentTarget);
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- handleLoadNewer intentionally
    // omitted: it's a plain function (not memoized) recreated every render, and including it
    // would tear down/recreate the observer on every render for no behavioral difference — the
    // observer's callback always closes over the latest handleLoadNewer via re-render anyway.
  }, [hasNewer, onLoadNewer, isLightboxOpen]);

  // Compensates scrollTop after loadNewer prepends photos above the current viewport, so the
  // content the user was already looking at doesn't visually jump downward. Runs synchronously
  // after the DOM updates (useLayoutEffect, not useEffect) so there's no visible flash before the
  // correction is applied.
  useLayoutEffect(() => {
    if (pendingPrependRef.current && mainContentRef?.current) {
      const { scrollHeightBefore } = pendingPrependRef.current;
      const delta = mainContentRef.current.scrollHeight - scrollHeightBefore;
      mainContentRef.current.scrollTop += delta;
      pendingPrependRef.current = null;
    }
  }, [photos, mainContentRef]);

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
        {handleUnlinkPerson && (
          <button
            type="button"
            className="unlink-person-btn"
            title={messages["unlinkPersonFromPhoto"]}
            onClick={(e) => { e.stopPropagation(); handleUnlinkPerson(p.id); }}
          >
            <Unlink2 size={14}/>
          </button>
        )}
        {badge}
      </div>
    );
  };

  const renderDisplayItem = (item) => {
    if (item.members.length === 1) {
      return renderPhotoCard(item.primary);
    }

    const badge = (
      <button
        type="button"
        className="duplicate-badge"
        title={messages["expandDuplicateGroup"]}
        onClick={(e) => { e.stopPropagation(); setOpenDuplicatePrimaryId(item.primary.id); }}
      >
        <Layers size={12} /> {messages["similarPhotosBadge"](item.members.length)}
      </button>
    );
    return renderPhotoCard(item.primary, badge);
  };

  return (
    <div className="content-body">
      {/* Only rendered once there's actually something newer to load (post-jump) — before that,
          rendering an empty sentinel here would just add a permanent gap above the normal,
          never-jumped Library grid for no reason. */}
      {onLoadNewer && hasNewer && (
        <div
          ref={topObserverTarget}
          style={{ height: '20px', margin: '0 0 20px 0', display: 'flex', justifyContent: 'center', alignItems: 'center' }}
        >
          <span style={{ color: '#888' }}>{messages["loadingMorePhotos"]}</span>
        </div>
      )}
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

      <DuplicateClusterModal
        item={openDuplicateItem}
        onClose={() => setOpenDuplicatePrimaryId(null)}
        handleMoveToGarbage={handleMoveToGarbage}
        handleUnlinkDuplicate={handleUnlinkDuplicate}
        onExpand={setSelectedPhoto}
      />
    </div>
  );
}