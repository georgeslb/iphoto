import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { Check, Layers, Unlink2, Move } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale
import DuplicateClusterModal from './DuplicateClusterModal';
import TimelineScrubber from './TimelineScrubber';
import { computeReorderDates } from '../utils/photoReorder';
import { useDragAutoScroll } from '../hooks/useDragAutoScroll';

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
  // Drag-to-reorder within a day (usePhotoActions.js#handleReorderPhotos) - optional: only
  // Library/Album/Space/Person pass this, so the drag handle simply never renders for
  // SharedAlbumView.jsx's public, unauthenticated grid (same convention handleUnlinkPerson already
  // uses above - a feature is entirely omitted when its handler prop isn't supplied).
  handleReorderPhotos,
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

  // Drag-to-reorder within a day (see utils/photoReorder.js for the actual date-math, and the
  // handle's own JSX below for the draggable element). Plain useState (not a ref) since both drive
  // visible re-renders - dimming every moved card, and highlighting whichever card is currently
  // being dragged over.
  // dragState: { movedIds, day } while a drag is in progress, else null.
  const [dragState, setDragState] = useState(null);
  // Which "gap" between two photos (or before the first/after the last) is currently under the
  // cursor - an index into that day's non-moved primaries, in display order (0 = before the very
  // first one, others.length = after the very last one). See renderDropZone/the per-day render
  // loop below for how these gaps become actual, generously-sized drop targets rather than
  // requiring the user to figure out which half of a specific photo to hover.
  const [dropZoneIndex, setDropZoneIndex] = useState(null);

  // Automatically scroll the container/window when moving assets near top or bottom edges
  useDragAutoScroll(Boolean(dragState), gridContainerRef);

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

  // Drag-to-reorder within a day. Only PRIMARIES participate - both as draggable handles and as
  // drop targets - a duplicate cluster's non-primary members only ever exist nested inside the
  // primary's own `duplicates` array (see clusterIntoItems above) and aren't independently
  // reorderable.
  const handleDragStart = (p, day, e) => {
    const dayPrimaryIds = groups[day].map(it => it.primary.id);
    // Dragging one of several currently-selected photos moves the WHOLE selection together (per
    // spec), but "limited to the same day" for multi-select means any selected id belonging to a
    // DIFFERENT day is silently excluded here - filtering dayPrimaryIds down to selectedIds both
    // enforces that and preserves this day's own current display order (X1..XN, nearest-P-first)
    // that utils/photoReorder.js's cascade needs.
    const movedIds = (selectedIds.includes(p.id) && selectedIds.length > 1)
        ? dayPrimaryIds.filter(id => selectedIds.includes(id))
        : [p.id];
    // dataTransfer setup MUST happen before anything that could trigger a React re-render - the
    // browser is still finalizing this drag session synchronously while this handler runs, and a
    // re-render that touches the dragged element (even just adding a CSS class) can make the
    // browser think its drag source changed/vanished, silently cancelling the whole session
    // (dragend fires immediately, with zero dragover events ever reaching anything - exactly what
    // was reported here). effectAllowed/setData first, then defer the actual state update itself
    // to the next tick (setTimeout 0) so this handler returns cleanly to the browser before React
    // processes it - the standard workaround for HTML5 drag-and-drop plus a React re-render.
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', p.id); // Firefox requires data to be set for drag to start
    setTimeout(() => setDragState({ movedIds, day }), 0);
  };

  const handleDragEnd = () => {
    // Safety net for a cancelled/off-target drop (e.g. Escape, or dropped outside any zone) -
    // handleZoneDrop already clears both below on a successful drop, this just guarantees it
    // either way.
    setDragState(null);
    setDropZoneIndex(null);
  };

  // computeReorderDates (utils/photoReorder.js) takes a targetId + before/after position rather
  // than a plain insertion index - translates a zone index back into that shape without having to
  // touch that already-tested utility's signature. index === others.length means "insert after
  // everything" (the day's oldest/last position, no "after" neighbor to anchor a targetId on).
  const zoneToTargetPosition = (others, index) =>
      index < others.length
          ? { targetId: others[index].id, position: 'before' }
          : { targetId: others[others.length - 1]?.id, position: 'after' };

  // Shared guard for onDragOver/onDrop below: rejects (no visual indicator, no-op drop) a zone in
  // a day other than the one being dragged (the "limited to the same day" constraint) or no drag
  // in progress at all.
  const isValidDropDay = (day) => dragState && dragState.day === day;

  const handleZoneDragOver = (day, zoneIndex, e) => {
    if (!isValidDropDay(day)) return;
    e.preventDefault(); // required to allow a drop at all
    if (dropZoneIndex !== zoneIndex) setDropZoneIndex(zoneIndex);
  };

  const handleZoneDragLeave = (zoneIndex) => {
    setDropZoneIndex(prev => (prev === zoneIndex ? null : prev));
  };

  const handleZoneDrop = (day, zoneIndex, e) => {
    if (!isValidDropDay(day)) return;
    e?.preventDefault?.();
    const currentDragState = dragState || touchStateRef.current;
    if (!currentDragState) return;
    const others = groups[day]
        .map(it => it.primary)
        .filter(pp => !currentDragState.movedIds.includes(pp.id));
    const { targetId, position } = zoneToTargetPosition(others, zoneIndex);
    const updates = targetId ? computeReorderDates(others, currentDragState.movedIds, targetId, position) : [];
    if (updates.length > 0) {
      setSelectedIds?.([]);
      handleReorderPhotos?.(updates);
    }
    touchStateRef.current = null;
    setDragState(null);
    setDropZoneIndex(null);
  };

  // Touch drag support for mobile browsers (touch devices do not fire HTML5 drag/drop events
  // and instead trigger context menus on long-press)
  const touchStateRef = useRef(null);

  const handleTouchStart = (p, day, e) => {
    if (e.cancelable) {
      e.preventDefault();
    }
    const dayPrimaryIds = groups[day].map(it => it.primary.id);
    const movedIds = (selectedIds.includes(p.id) && selectedIds.length > 1)
        ? dayPrimaryIds.filter(id => selectedIds.includes(id))
        : [p.id];
    touchStateRef.current = {
      movedIds,
      day,
      lastZoneIndex: null,
    };
    setDragState({ movedIds, day });
  };

  const handleTouchMove = (e) => {
    if (!touchStateRef.current) return;
    if (e.cancelable) {
      e.preventDefault();
    }
    const touch = e.touches[0];

    // Mobile touch auto-scrolling
    const container = gridContainerRef.current?.closest('.main-content') || document.querySelector('.main-content');
    if (container) {
      const rect = container.getBoundingClientRect ? container.getBoundingClientRect() : { top: 0, bottom: window.innerHeight };
      const top = Math.max(0, rect.top);
      const bottom = Math.min(window.innerHeight, rect.bottom);
      const zoneTop = 220;
      const zoneBottom = 150;
      if (touch.clientY < top + zoneTop) {
        const intensity = Math.min(1, Math.max(0, (top + zoneTop - touch.clientY) / zoneTop));
        const speed = -Math.max(2, Math.round(intensity * 16));
        if (typeof container.scrollBy === 'function') {
          container.scrollBy(0, speed);
        } else {
          container.scrollTop += speed;
        }
      } else if (touch.clientY > bottom - zoneBottom) {
        const intensity = Math.min(1, Math.max(0, (touch.clientY - (bottom - zoneBottom)) / zoneBottom));
        const speed = Math.max(2, Math.round(intensity * 16));
        if (typeof container.scrollBy === 'function') {
          container.scrollBy(0, speed);
        } else {
          container.scrollTop += speed;
        }
      }
    }

    const el = document.elementFromPoint(touch.clientX, touch.clientY);
    const zoneEl = el?.closest?.('.drop-zone');
    if (zoneEl) {
      const zoneDay = zoneEl.getAttribute('data-day');
      const zIndex = parseInt(zoneEl.getAttribute('data-zone-index'), 10);
      if (zoneDay === touchStateRef.current.day && !isNaN(zIndex)) {
        touchStateRef.current.lastZoneIndex = zIndex;
        if (dropZoneIndex !== zIndex) setDropZoneIndex(zIndex);
        return;
      }
    }

    const cardEl = el?.closest?.('.photo-card');
    if (cardEl) {
      const cardDay = cardEl.getAttribute('data-card-day');
      const cardZone = parseInt(cardEl.getAttribute('data-card-zone'), 10);
      if (cardDay === touchStateRef.current.day && !isNaN(cardZone)) {
        const rect = cardEl.getBoundingClientRect();
        const isRightHalf = touch.clientX > (rect.left + rect.width / 2);
        const targetZone = isRightHalf ? cardZone + 1 : cardZone;
        touchStateRef.current.lastZoneIndex = targetZone;
        if (dropZoneIndex !== targetZone) setDropZoneIndex(targetZone);
      }
    }
  };

  const handleTouchEnd = (e) => {
    if (!touchStateRef.current) return;
    const { day, lastZoneIndex } = touchStateRef.current;
    const targetZone = dropZoneIndex !== null ? dropZoneIndex : lastZoneIndex;
    if (targetZone !== null) {
      handleZoneDrop(day, targetZone, e);
    } else {
      touchStateRef.current = null;
      setDragState(null);
      setDropZoneIndex(null);
    }
  };

  const handleTouchCancel = () => {
    touchStateRef.current = null;
    setDragState(null);
    setDropZoneIndex(null);
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
    // Ordered day keys, matching top-to-bottom display order - used below to prefetch by day
    // COUNT rather than relying on pixel rootMargin alone (see the look-ahead comment).
    const dayOrder = dayIndex.days.map(d => d.day);
    const observer = new IntersectionObserver(
      entries => {
        entries.forEach(entry => {
          if (!entry.isIntersecting) return;
          const day = entry.target.getAttribute('data-day-sentinel');
          if (!day) return;
          dayIndex.loadDayPage(day);
          // Look-ahead prefetch (user request, 2026-09-11): start loading the day TWO positions
          // further down as soon as the user reaches this day's last currently-loaded assets,
          // instead of only reacting once that later day's OWN sentinel scrolls into range. An
          // unloaded day's sentinel sits right at the end of its own (empty, until loaded) group,
          // i.e. right after the previous day's content - so without this, how many days ahead
          // actually got warmed up was really governed by the rootMargin's pixel distance, which
          // varies wildly with how many photos/how tall each day's content happens to be. Fetching
          // 2 days ahead explicitly keeps a steady day-count buffer regardless of day density.
          const aheadDay = dayOrder[dayOrder.indexOf(day) + 2];
          if (aheadDay) dayIndex.loadDayPage(aheadDay);
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

  // A drop target sits in the "blank space" BETWEEN two photos (or before the first/after the
  // last) rather than requiring the user to figure out which half of a specific photo card to
  // hover - see the per-day render loop below for how these get interleaved with the actual cards,
  // one per possible insertion point. Idle (not currently hovered), it's a slim but generously
  // hit-testable strip with a subtle dashed hint so it reads as an interactive gap even before
  // hovering it. The zone div ITSELF never changes size when hovered (see App.css's .drop-zone
  // comment for why - resizing it used to break drops on anything but the first zone in a row);
  // the full preview rectangle (sized off the first moved photo's own aspect ratio - a multi-item
  // drag can mix aspect ratios, this is a preview, not a pixel-exact one - with a count badge when
  // more than one photo is moving) is a purely visual absolutely-positioned overlay on top of it.
  // `size` matches whatever every card in this day is currently rendered at (see the per-day render
  // loop's `cardSize` - shrunk while dragging, see its own comment) so the zone/preview's own height
  // lines up with its flanking cards rather than a hardcoded value going stale the moment cards shrink.
  const renderDropZone = (day, zoneIndex, isActive, aspectRatio, count, size) => (
    <div
      key={`zone-${zoneIndex}`}
      className="drop-zone"
      data-day={day}
      data-zone-index={zoneIndex}
      style={{ height: `${size}px` }}
      onDragOver={(e) => handleZoneDragOver(day, zoneIndex, e)}
      onDragLeave={() => handleZoneDragLeave(zoneIndex)}
      onDrop={(e) => handleZoneDrop(day, zoneIndex, e)}
    >
      {isActive && (
        <div className="drop-zone-preview" style={{ width: `${aspectRatio * size}px`, height: `${size}px` }}>
          {count > 1 && <span className="drop-placeholder-count">{count}</span>}
        </div>
      )}
    </div>
  );

  const renderPhotoCard = (p, day, isDropNeighbor, badge, size = 220, zoneIndex = 0) => {
    const aspectRatio = (p.width && p.height) ? (p.width / p.height) : 1;
    const isSelected = selectedIds.includes(p.id);
    const isDragging = dragState?.movedIds.includes(p.id);
    return (
      <div
        key={p.id}
        data-card-day={day}
        data-card-zone={zoneIndex}
        className={`photo-card ${isSelected ? 'selected' : ''} ${isDragging ? 'dragging' : ''} ${isDropNeighbor ? 'drop-neighbor' : ''}`}
        style={{ flexBasis: `${aspectRatio * size}px`, height: `${size}px` }}
        onClick={(e) => handlePhotoClick(p, e)}
        onContextMenu={(e) => e.preventDefault()}
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
        {/* Drag-to-reorder handle - only the handle itself is draggable (not the whole card), so
            the card's existing click-to-select/open-lightbox behavior above is undisturbed. Only
            rendered when a reorder handler is actually supplied (see this component's own prop
            comment - omitted entirely for SharedAlbumView.jsx's public grid). */}
        {handleReorderPhotos && (
          <button
            type="button"
            className="drag-handle"
            draggable
            onDragStart={(e) => handleDragStart(p, day, e)}
            onDragEnd={handleDragEnd}
            onTouchStart={(e) => handleTouchStart(p, day, e)}
            onTouchMove={handleTouchMove}
            onTouchEnd={handleTouchEnd}
            onTouchCancel={handleTouchCancel}
            onContextMenu={(e) => e.preventDefault()}
            onClick={(e) => e.stopPropagation()}
            title={messages["dragToReorder"]}
          >
            <Move size={14} />
          </button>
        )}
        {badge}
      </div>
    );
  };

  const renderDisplayItem = (item, day, isDropNeighbor, size = 220, zoneIndex = 0) => {
    if (item.members.length === 1) {
      return renderPhotoCard(item.primary, day, isDropNeighbor, undefined, size, zoneIndex);
    }

    const badge = (
      <button
        type="button"
        className="duplicate-badge"
        title={messages["expandDuplicateGroup"]}
        onClick={(e) => { e.stopPropagation(); setOpenDuplicateMemberIds(item.members.map(m => m.id)); }}
      >
        <Layers size={12} /> {item.members.length}<span className="duplicate-badge-label"> {messages["similarLabel"]}</span>
      </button>
    );
    return renderPhotoCard(item.primary, day, isDropNeighbor, badge, size, zoneIndex);
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

        // Drag-to-reorder: while dragging within THIS day, `others` is every non-moved primary in
        // display order - one drop zone (see renderDropZone) is rendered before each of them, plus
        // one trailing zone after the last, so every possible insertion point is an explicit,
        // always-present, generously-sized target the user can see and hover directly, rather than
        // having to find the right half of a specific photo. null (not dragging this day) means no
        // zones render at all and the row looks exactly like it does outside a drag.
        const others = (dragState?.day === day)
            ? dayItems.map(it => it.primary).filter(p => !dragState.movedIds.includes(p.id))
            : null;
        // Whichever zone is currently hovered determines the "make room" preview: its two flanking
        // photos (the ones that would end up immediately before/after the moved item(s) once
        // dropped) shrink slightly, and that zone itself expands into a full preview rectangle
        // (see renderDropZone) instead of its idle slim strip.
        const neighborBeforeId = (others && dropZoneIndex != null) ? (others[dropZoneIndex - 1]?.id ?? null) : null;
        const neighborAfterId = (others && dropZoneIndex != null) ? (others[dropZoneIndex]?.id ?? null) : null;
        // Shrinking every card in this day (not just the two immediate neighbors above, which only
        // get a cosmetic transform - see .drop-neighbor) by a real, layout-affecting amount while a
        // drag is active here is what actually keeps everything else from reflowing to a different
        // row/position: the drop zones inserted below take up real flex-basis width of their own
        // (see .drop-zone), and on a fairly full row that extra width alone can push flex-wrap to
        // wrap differently than before the drag started. Shrinking every card back down reclaims
        // roughly that much width across the row, so wrap points stay close to where they already
        // were rather than the whole day visibly reshuffling the moment a drag begins.
        const cardSize = (others) ? 200 : 220;
        let placeholderAspectRatio = 1;
        if (others && dropZoneIndex != null) {
          const draggedPrimary = dayItems.find(it => dragState.movedIds.includes(it.primary.id))?.primary;
          if (draggedPrimary?.width && draggedPrimary?.height) {
            placeholderAspectRatio = draggedPrimary.width / draggedPrimary.height;
          }
        }

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
              {(() => {
                // Interleave: a zone before every non-moved item (tracked via othersIdx, which
                // only advances past items actually present in `others`) plus one trailing zone
                // after the last - see the `others`/zone comment above for why this is null (no
                // zones at all) outside a drag on this day.
                let othersIdx = 0;
                const children = dayItems.map(item => {
                  const isMoved = others ? !others.some(o => o.id === item.primary.id) : true;
                  const zone = (others && !isMoved)
                      ? renderDropZone(day, othersIdx, dropZoneIndex === othersIdx, placeholderAspectRatio, dragState.movedIds.length, cardSize)
                      : null;
                  const currentCardZone = othersIdx;
                  if (others && !isMoved) othersIdx++;
                  const card = renderDisplayItem(item, day, item.primary.id === neighborBeforeId || item.primary.id === neighborAfterId, cardSize, currentCardZone);
                  return (
                    <React.Fragment key={item.primary.id}>
                      {zone}
                      {card}
                    </React.Fragment>
                  );
                });
                return (
                  <>
                    {children}
                    {others && renderDropZone(day, othersIdx, dropZoneIndex === othersIdx, placeholderAspectRatio, dragState.movedIds.length, cardSize)}
                  </>
                );
              })()}
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
