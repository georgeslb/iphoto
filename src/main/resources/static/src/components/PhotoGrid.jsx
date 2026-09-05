import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { Check, Layers, Unlink2 } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale
import DuplicateClusterModal from './DuplicateClusterModal';
import TimelineScrubber from './TimelineScrubber';

// Clusters a flat list of photos into {primary, members} display items, same rule everywhere
// duplicates are populated (Library/Space, day-indexed or not): a photo carries its known
// duplicates via `p.duplicates`; `consumedIds` (shared across every call for one render, so a
// duplicate discovered while processing one bucket is never later re-rendered as its own
// top-level item from a different bucket) prevents a duplicate from being double-rendered.
function clusterIntoItems(photosArray, consumedIds) {
  const items = [];
  photosArray.forEach(p => {
    if (consumedIds.has(p.id)) return;
    consumedIds.add(p.id);
    const duplicateMembers = (p.duplicates || []).filter(d => !consumedIds.has(d.id));
    duplicateMembers.forEach(d => consumedIds.add(d.id));
    items.push({
      primary: p,
      members: duplicateMembers.length > 0 ? [p, ...duplicateMembers] : [p],
    });
  });
  return items;
}

export default function PhotoGrid({
  type,
  selectedIds,
  setSelectedIds,
  setSelectedPhoto,
  isLightboxOpen, // Accept the new prop
  handleMoveToGarbage,
  handleUnlinkDuplicate, // DuplicateClusterModal's unlink icon
  handleUnlinkPerson, // Only passed from PersonDetail.jsx — unlinks this person from a photo
  // Day-index browsing (hooks/useDayIndexedPhotos.js) - every photo-listing view (Library/Album/
  // Space/Garbage/Person) uses this; renders one section per day, fetched lazily as it scrolls
  // into view.
  dayIndex,
}) {
  const { messages } = useLocale(); // Use the useLocale hook
  const [hoveredVideoId, setHoveredVideoId] = useState(null);
  // Which duplicate cluster's "×N similar" popup is open (DuplicateClusterModal.jsx, replacing
  // the old expand-in-place row) — every MEMBER id the cluster had at the moment it was opened,
  // not the item/primary object itself, so the modal always re-derives its current member list
  // from the live `groups` below (computed after this) rather than a stale snapshot. That matters
  // because moving a member to garbage from inside the modal changes `photos`/`groups` on the
  // next render, and the modal should reflect that (member disappears) rather than keep showing
  // an already-garbage-collected photo until manually closed.
  //
  // Tracking every member id (not just the primary's) matters specifically for garbage-ing the
  // PRIMARY itself (the suggested keep): a cluster's non-primary members only ever exist nested
  // inside the primary's own `duplicates` array (see utils/photoListUpdates.js), so removing the
  // primary promotes a survivor to take its place with a DIFFERENT id. Anchoring on the primary's
  // id alone would find nothing after that promotion (that exact id is now genuinely gone) and
  // wrongly close the modal even though the cluster itself is still very much alive, just under a
  // new primary - matching on ANY surviving member id finds it correctly either way.
  const [openDuplicateMemberIds, setOpenDuplicateMemberIds] = useState(null);
  const gridContainerRef = useRef(null);
  const lastClickedIndexRef = useRef(null);

  // "Reading order" flat list used for shift-click range selection and the duplicate-cluster
  // lookup below - every currently-loaded photo across every day section, in server day order.
  // Memoized off the underlying stable state (not the `dayIndex` wrapper object itself, which is
  // a fresh object literal every render) so this only recomputes when the actual data changes.
  const flatSelectionPhotos = useMemo(
    () => dayIndex.days.flatMap(d => dayIndex.photosByDay[d.day] || []),
    [dayIndex.days, dayIndex.photosByDay]
  );

  const photoIndexById = useMemo(() => {
    const map = new Map();
    flatSelectionPhotos.forEach((p, i) => map.set(p.id, i));
    return map;
  }, [flatSelectionPhotos]);

  const selectRange = (toIndex) => {
    const fromIndex = lastClickedIndexRef.current;
    const start = Math.min(fromIndex, toIndex);
    const end = Math.max(fromIndex, toIndex);
    const rangeIds = flatSelectionPhotos.slice(start, end + 1).map(p => p.id);
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

  // One entry per day from the index (already sorted newest-first by the server), even for a day
  // with nothing loaded yet - that's what lets its section render as a placeholder/sentinel
  // immediately instead of only appearing once scrolled to. Buckets are known upfront here (from
  // the day index), so clusterIntoItems can run once per bucket.
  const groups = {};
  const groupOrder = [];
  const consumedIds = new Set();
  dayIndex.days.forEach(({ day }) => {
    groupOrder.push(day);
    groups[day] = clusterIntoItems(dayIndex.photosByDay[day] || [], consumedIds);
  });

  // Re-derived every render from the live `groups` above (see openDuplicateMemberIds' own comment
  // for why this matches on ANY member id, not just the primary's) — null once none of the
  // tracked ids match anything anymore, which also naturally closes the modal if its entire
  // cluster is ever fully cleared out from under it.
  const openDuplicateItem = openDuplicateMemberIds
    ? Object.values(groups).flat().find(it => it.members.some(m => openDuplicateMemberIds.includes(m.id))) || null
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

  // Day-indexed pagination: ONE shared IntersectionObserver for every day section's sentinel
  // (a library can easily have hundreds of populated days - a naive per-section observer/effect
  // pair, the pattern flat mode's bottom sentinel above uses, would not scale to that). Each
  // sentinel carries its own day as a data attribute; whichever ones are actually in the DOM are
  // (re-)queried and observed whenever the loaded data changes, since a day's sentinel is only
  // rendered while that day still has more pages to load (see the JSX below) and so naturally
  // disappears once a day finishes loading. A generous rootMargin matters more here than in flat
  // mode: day sections can be short (a handful of photos), so several day boundaries can go by in
  // one ordinary scroll gesture - a small margin means the "next day" sentinel only enters range
  // (and its fetch only starts) right as the user reaches it, showing a brief empty/loading
  // section every time. A larger margin gets that day's first page (and a currently-open day's
  // next page) loading well before it's actually reached.
  useEffect(() => {
    if (isLightboxOpen) return undefined;
    const observer = new IntersectionObserver(
      entries => {
        entries.forEach(entry => {
          if (!entry.isIntersecting) return;
          const day = entry.target.getAttribute('data-day-sentinel');
          if (day) dayIndex.loadDayPage(day);
        });
      },
      // Symmetric top/bottom margin - every not-yet-loaded day (regardless of position) already
      // renders its own sentinel up front (see groups/groupOrder above, built from the full
      // dayIndex.days list), so scrolling UP after a TimelineScrubber jump needs to prefetch
      // upward the same way scrolling down already prefetches downward, not just load once a
      // section is already on screen.
      { threshold: 0.1, rootMargin: '1500px 0px 1500px 0px' }
    );

    const sentinels = gridContainerRef.current?.querySelectorAll('[data-day-sentinel]') || [];
    sentinels.forEach(el => observer.observe(el));

    return () => observer.disconnect();
    // dayIndex itself deliberately excluded: it's a fresh object every render (see
    // flatSelectionPhotos above), which would tear down and recreate the observer every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dayIndex.days, dayIndex.photosByDay, dayIndex.dayPageState, isLightboxOpen, dayIndex.loadDayPage]);

  // TimelineScrubber jump target: an INSTANT (not smooth) scroll straight to that day's section -
  // smooth scrolling would transiently intersect every sentinel it animates past, defeating the
  // whole point of jumping without fetching everything in between. Also eagerly triggers
  // loadDayPage itself rather than waiting on the IntersectionObserver to notice the new scroll
  // position, so the jump feels instant instead of depending on observer timing.
  const seekToDay = useCallback((day) => {
    const el = gridContainerRef.current?.querySelector(`[data-day-group="${day}"]`);
    if (el) el.scrollIntoView({ block: 'start' });
    dayIndex.loadDayPage(day);
    // dayIndex itself deliberately excluded - see the day-sentinel observer effect above.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dayIndex.loadDayPage]);

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
        onClick={(e) => { e.stopPropagation(); setOpenDuplicateMemberIds(item.members.map(m => m.id)); }}
      >
        <Layers size={12} /> {messages["similarPhotosBadge"](item.members.length)}
      </button>
    );
    return renderPhotoCard(item.primary, badge);
  };

  return (
    <div className="content-body" ref={gridContainerRef}>
      <TimelineScrubber days={dayIndex.days} onSeek={seekToDay} />
      {dayIndex.loadingDays && (
        <div style={{ padding: '40px', textAlign: 'center', color: '#888' }}>{messages["loadingMorePhotos"]}</div>
      )}
      {!dayIndex.loadingDays && dayIndex.days.length === 0 && (
        <div style={{ padding: '40px', textAlign: 'center', color: '#888' }}>{messages["noPhotosInView"]}</div>
      )}
      {groupOrder.map(day => {
        const dayItems = groups[day];
        const groupIds = dayItems.flatMap(item => item.members.map(m => m.id));
        const allSelected = groupIds.length > 0 && groupIds.every(id => selectedIds.includes(id));
        const pageState = dayIndex.dayPageState[day];
        const showDaySentinel = !pageState?.isLast;

        return (
          <div key={day} className="day-group" data-day-group={day}>
            <div className="day-header" style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <h3>{day}</h3>
              {groupIds.length > 0 && (
                <div
                  className={`check-circle day-check ${allSelected ? 'checked' : ''}`}
                  style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', width: '20px', height: '20px', borderRadius: '50%', border: '2px solid #ccc' }}
                  onClick={(e) => handleSelectGroup(dayItems, e)}
                >
                  {allSelected && <Check size={14} color="white" />}
                </div>
              )}
            </div>
            <div className="photo-grid">
              {dayItems.map(item => renderDisplayItem(item))}
            </div>
            {showDaySentinel && (
              <div
                data-day-sentinel={day}
                style={{ height: '20px', margin: '20px 0', display: 'flex', justifyContent: 'center', alignItems: 'center' }}
              >
                <span style={{ color: '#888' }}>{messages["loadingMorePhotos"]}</span>
              </div>
            )}
          </div>
        )
      })}

      <DuplicateClusterModal
        item={openDuplicateItem}
        onClose={() => setOpenDuplicateMemberIds(null)}
        handleMoveToGarbage={handleMoveToGarbage}
        handleUnlinkDuplicate={handleUnlinkDuplicate}
        onExpand={setSelectedPhoto}
      />
    </div>
  );
}
