import React, { useState, useEffect, useRef, useMemo, useCallback } from 'react';
import { Check, Layers, Unlink2, Move } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale
import DuplicateClusterModal from './DuplicateClusterModal';
import TimelineScrubber from './TimelineScrubber';
import { computeReorderDates } from '../utils/photoReorder';
import { useDragAutoScroll } from '../hooks/useDragAutoScroll';

// How long the user must hold mouse/touch on an asset before drag-to-reorder activates.
const REORDER_HOLD_MS = 2000;
const REORDER_MOVE_SLOP_PX = 10;

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

  // Reorder selection (checked via the move button, scoped to a single day just like Android).
  const [reorderSelectedIds, setReorderSelectedIds] = useState([]);
  const [reorderDay, setReorderDay] = useState(null);

  // Drag-to-reorder within a day (see utils/photoReorder.js for the actual date-math).
  // dragState: { movedIds, day, clientX, clientY, leadPhoto } while a drag is in progress, else null.
  const [dragState, setDragState] = useState(null);
  // dropTarget: { targetId, position: 'before' | 'after', day } while dragging over a target photo
  const [dropTarget, setDropTarget] = useState(null);

  const holdTimerRef = useRef(null);
  const holdStartRef = useRef(null);
  const pendingHoldCleanupRef = useRef(null);
  const suppressClickUntilRef = useRef(0);
  const dragPositionRef = useRef({ clientX: 0, clientY: 0 });
  const dragStateRef = useRef(null);
  const dropTargetRef = useRef(null);
  const groupsRef = useRef({});
  const reorderStateRef = useRef({ ids: [], day: null });
  const selectedIdsRef = useRef(selectedIds);
  const handleReorderPhotosRef = useRef(handleReorderPhotos);
  const setSelectedIdsRef = useRef(setSelectedIds);

  dragStateRef.current = dragState;
  dropTargetRef.current = dropTarget;
  reorderStateRef.current = { ids: reorderSelectedIds, day: reorderDay };
  selectedIdsRef.current = selectedIds;
  handleReorderPhotosRef.current = handleReorderPhotos;
  setSelectedIdsRef.current = setSelectedIds;

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
    if (Date.now() < suppressClickUntilRef.current) {
      suppressClickUntilRef.current = 0;
      e.stopPropagation();
      return;
    }
    if (reorderSelectedIds.length > 0) {
      setReorderSelectedIds([]);
      setReorderDay(null);
    }
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
    if (reorderSelectedIds.length > 0) {
      setReorderSelectedIds([]);
      setReorderDay(null);
    }
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
  groupsRef.current = groups;

  // Re-derived every render from the live `groups` above (see openDuplicateMemberIds' own comment
  // for why this matches on ANY member id, not just the primary's) — null once none of the
  // tracked ids match anything anymore, which also naturally closes the modal if its entire
  // cluster is ever fully cleared out from under it.
  const openDuplicateItem = openDuplicateMemberIds
    ? Object.values(groups).flat().find(it => it.members.some(m => openDuplicateMemberIds.includes(m.id))) || null
    : null;

  const handleSelectGroup = (dayItems, e) => {
    e.stopPropagation();
    if (reorderSelectedIds.length > 0) {
      setReorderSelectedIds([]);
      setReorderDay(null);
    }
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

  // Toggle an asset in reorder selection (scoped to a single day). Only PRIMARIES participate -
  // duplicate cluster non-primary members only exist inside the primary's `duplicates` array.
  const handleReorderButtonClick = (p, day, e) => {
    e.stopPropagation();
    if (Date.now() < suppressClickUntilRef.current) {
      suppressClickUntilRef.current = 0;
      return;
    }
    if (selectedIds.length > 0) {
      setSelectedIds?.([]);
    }
    if (reorderDay && reorderDay !== day) {
      setReorderDay(day);
      setReorderSelectedIds([p.id]);
      return;
    }
    if (reorderSelectedIds.includes(p.id)) {
      const next = reorderSelectedIds.filter(id => id !== p.id);
      setReorderSelectedIds(next);
      if (next.length === 0) {
        setReorderDay(null);
      }
    } else {
      setReorderDay(day);
      setReorderSelectedIds(prev => (reorderDay === day ? [...prev, p.id] : [p.id]));
    }
  };

  const clearHoldTimer = useCallback(() => {
    if (holdTimerRef.current) {
      clearTimeout(holdTimerRef.current);
      holdTimerRef.current = null;
    }
    holdStartRef.current = null;
    if (pendingHoldCleanupRef.current) {
      pendingHoldCleanupRef.current();
      pendingHoldCleanupRef.current = null;
    }
  }, []);

  const resolveDropTarget = useCallback((day, movedIds, clientX, clientY) => {
    const container = gridContainerRef.current;
    if (!container) return null;

    if (typeof document.elementFromPoint === 'function') {
      const hitEl = document.elementFromPoint(clientX, clientY);
      const hitCard = hitEl?.closest?.('.photo-card');
      if (hitCard) {
        const cardDay = hitCard.getAttribute('data-card-day');
        const cardId = hitCard.getAttribute('data-photo-id');
        if (cardDay === day && cardId && !movedIds.includes(cardId)) {
          const rect = hitCard.getBoundingClientRect();
          const position = clientX < (rect.left + rect.width / 2) ? 'before' : 'after';
          return { targetId: cardId, position, day };
        }
      }
    }

    const cards = Array.from(container.querySelectorAll(`.photo-card[data-card-day="${day}"]`));
    let best = null;
    let bestScore = null;

    for (const card of cards) {
      const cardId = card.getAttribute('data-photo-id');
      if (!cardId || movedIds.includes(cardId)) continue;
      const rect = card.getBoundingClientRect();
      const dx = clientX < rect.left ? rect.left - clientX : clientX > rect.right ? clientX - rect.right : 0;
      const dy = clientY < rect.top ? rect.top - clientY : clientY > rect.bottom ? clientY - rect.bottom : 0;
      const centerDistSq = Math.pow(clientX - (rect.left + rect.width / 2), 2)
        + Math.pow(clientY - (rect.top + rect.height / 2), 2);
      if (
        !bestScore ||
        dy < bestScore.dy ||
        (dy === bestScore.dy && dx < bestScore.dx) ||
        (dy === bestScore.dy && dx === bestScore.dx && centerDistSq < bestScore.centerDistSq)
      ) {
        bestScore = { dy, dx, centerDistSq };
        const position = clientX < (rect.left + rect.width / 2) ? 'before' : 'after';
        best = { targetId: cardId, position, day };
      }
    }

    return best;
  }, []);

  const startHold = useCallback((p, day, clientX, clientY, target) => {
    if (!handleReorderPhotosRef.current || dragStateRef.current) return;
    if (target?.closest?.('.select-overlay, .duplicate-badge, .unlink-person-btn, .drag-handle')) return;

    clearHoldTimer();
    holdStartRef.current = {
      photo: p,
      day,
      startX: clientX,
      startY: clientY,
      clientX,
      clientY,
    };

    const handlePendingMove = (ev) => {
      if (!holdStartRef.current) return;
      const pt = ev.touches?.[0] || ev;
      holdStartRef.current.clientX = pt.clientX;
      holdStartRef.current.clientY = pt.clientY;
      const dist = Math.hypot(
        pt.clientX - holdStartRef.current.startX,
        pt.clientY - holdStartRef.current.startY
      );
      if (dist > REORDER_MOVE_SLOP_PX) {
        clearHoldTimer();
      }
    };

    const handlePendingEnd = () => {
      clearHoldTimer();
    };

    window.addEventListener('mousemove', handlePendingMove);
    window.addEventListener('mouseup', handlePendingEnd);
    window.addEventListener('touchmove', handlePendingMove, { passive: true });
    window.addEventListener('touchend', handlePendingEnd);
    window.addEventListener('touchcancel', handlePendingEnd);

    pendingHoldCleanupRef.current = () => {
      window.removeEventListener('mousemove', handlePendingMove);
      window.removeEventListener('mouseup', handlePendingEnd);
      window.removeEventListener('touchmove', handlePendingMove);
      window.removeEventListener('touchend', handlePendingEnd);
      window.removeEventListener('touchcancel', handlePendingEnd);
    };

    holdTimerRef.current = setTimeout(() => {
      const holdInfo = holdStartRef.current;
      clearHoldTimer();
      if (!holdInfo) return;

      // Prevent the subsequent click/mouseup from opening the lightbox
      suppressClickUntilRef.current = Date.now() + 400;

      const dayPrimaryIds = (groupsRef.current[holdInfo.day] || []).map(it => it.primary.id);
      const { ids: activeReorderIds, day: activeReorderDay } = reorderStateRef.current;
      const baseSet = activeReorderDay === holdInfo.day ? new Set(activeReorderIds) : new Set();
      baseSet.add(holdInfo.photo.id);

      const movedIds = dayPrimaryIds.filter(id => baseSet.has(id));
      const finalMovedIds = movedIds.length > 0 ? movedIds : [holdInfo.photo.id];

      if (selectedIdsRef.current.length > 0) {
        setSelectedIdsRef.current?.([]);
      }
      setReorderDay(holdInfo.day);
      setReorderSelectedIds(finalMovedIds);

      if (typeof navigator !== 'undefined' && typeof navigator.vibrate === 'function') {
        try {
          navigator.vibrate(40);
        } catch (_) {
          // Ignore environments disallowing vibration without user gesture
        }
      }

      dragPositionRef.current = { clientX: holdInfo.clientX, clientY: holdInfo.clientY };
      const initialTarget = resolveDropTarget(holdInfo.day, finalMovedIds, holdInfo.clientX, holdInfo.clientY);
      dropTargetRef.current = initialTarget;
      setDropTarget(initialTarget);
      setDragState({
        movedIds: finalMovedIds,
        day: holdInfo.day,
        clientX: holdInfo.clientX,
        clientY: holdInfo.clientY,
        leadPhoto: holdInfo.photo,
      });
    }, REORDER_HOLD_MS);
  }, [clearHoldTimer, resolveDropTarget]);

  const handleCardMouseDown = (p, day, e) => {
    if (e.button !== 0) return;
    startHold(p, day, e.clientX, e.clientY, e.target);
  };

  const handleCardTouchStart = (p, day, e) => {
    if (e.touches.length !== 1) {
      clearHoldTimer();
      return;
    }
    const touch = e.touches[0];
    startHold(p, day, touch.clientX, touch.clientY, e.target);
  };

  // Clean up any pending hold timer on unmount
  useEffect(() => clearHoldTimer, [clearHoldTimer]);

  // Active drag tracking (pointer coordinates, drop target resolution, edge scroll re-hit-testing, drop/cancel)
  const isDragActive = Boolean(dragState);
  useEffect(() => {
    if (!isDragActive) return undefined;

    const updateAtPosition = (clientX, clientY) => {
      const currentDrag = dragStateRef.current;
      if (!currentDrag) return;
      dragPositionRef.current = { clientX, clientY };
      setDragState(prev => (prev ? { ...prev, clientX, clientY } : null));
      const nextTarget = resolveDropTarget(currentDrag.day, currentDrag.movedIds, clientX, clientY);
      const prevTarget = dropTargetRef.current;
      if (
        nextTarget?.targetId !== prevTarget?.targetId ||
        nextTarget?.position !== prevTarget?.position ||
        nextTarget?.day !== prevTarget?.day
      ) {
        dropTargetRef.current = nextTarget;
        setDropTarget(nextTarget);
      }
    };

    const handleMouseMove = (e) => {
      updateAtPosition(e.clientX, e.clientY);
    };

    const handleTouchMove = (e) => {
      if (e.cancelable) {
        e.preventDefault();
      }
      const touch = e.touches?.[0];
      if (touch) {
        updateAtPosition(touch.clientX, touch.clientY);
      }
    };

    const finishDrag = (commit) => {
      suppressClickUntilRef.current = Date.now() + 400;
      const currentDrag = dragStateRef.current;
      const activeTarget = dropTargetRef.current;
      if (commit && currentDrag && activeTarget?.targetId) {
        const others = (groupsRef.current[currentDrag.day] || [])
          .map(it => it.primary)
          .filter(pp => !currentDrag.movedIds.includes(pp.id));
        const updates = computeReorderDates(others, currentDrag.movedIds, activeTarget.targetId, activeTarget.position);
        if (updates.length > 0) {
          setSelectedIdsRef.current?.([]);
          handleReorderPhotosRef.current?.(updates);
        }
      }
      dropTargetRef.current = null;
      setDragState(null);
      setDropTarget(null);
      setReorderSelectedIds([]);
      setReorderDay(null);
    };

    const handleMouseUp = () => finishDrag(true);
    const handleTouchEnd = () => finishDrag(true);
    const handleTouchCancel = () => finishDrag(false);
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') {
        finishDrag(false);
      }
    };
    const handleContainerScroll = () => {
      const { clientX, clientY } = dragPositionRef.current;
      const currentDrag = dragStateRef.current;
      if (!currentDrag) return;
      const nextTarget = resolveDropTarget(currentDrag.day, currentDrag.movedIds, clientX, clientY);
      const prevTarget = dropTargetRef.current;
      if (
        nextTarget?.targetId !== prevTarget?.targetId ||
        nextTarget?.position !== prevTarget?.position ||
        nextTarget?.day !== prevTarget?.day
      ) {
        dropTargetRef.current = nextTarget;
        setDropTarget(nextTarget);
      }
    };

    const scrollContainer = gridContainerRef.current?.closest?.('.main-content') || window;
    window.addEventListener('mousemove', handleMouseMove);
    window.addEventListener('mouseup', handleMouseUp);
    window.addEventListener('touchmove', handleTouchMove, { passive: false });
    window.addEventListener('touchend', handleTouchEnd);
    window.addEventListener('touchcancel', handleTouchCancel);
    window.addEventListener('keydown', handleKeyDown);
    scrollContainer.addEventListener('scroll', handleContainerScroll, { passive: true });

    return () => {
      window.removeEventListener('mousemove', handleMouseMove);
      window.removeEventListener('mouseup', handleMouseUp);
      window.removeEventListener('touchmove', handleTouchMove);
      window.removeEventListener('touchend', handleTouchEnd);
      window.removeEventListener('touchcancel', handleTouchCancel);
      window.removeEventListener('keydown', handleKeyDown);
      scrollContainer.removeEventListener('scroll', handleContainerScroll);
    };
  }, [isDragActive, resolveDropTarget]);

  // Allow clearing reorder selection via Escape even before starting a drag
  useEffect(() => {
    if (reorderSelectedIds.length === 0 || isDragActive) return undefined;
    const handleEscape = (e) => {
      if (e.key === 'Escape') {
        setReorderSelectedIds([]);
        setReorderDay(null);
      }
    };
    window.addEventListener('keydown', handleEscape);
    return () => window.removeEventListener('keydown', handleEscape);
  }, [reorderSelectedIds.length, isDragActive]);

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
  const renderPhotoCard = (p, day, badge, size = 220) => {
    const aspectRatio = (p.width && p.height) ? (p.width / p.height) : 1;
    const isSelected = selectedIds.includes(p.id);
    const isReorderSelected = reorderSelectedIds.includes(p.id);
    const isDragging = dragState?.movedIds.includes(p.id);
    const showReorderBtn = Boolean(handleReorderPhotos && (!reorderDay || reorderDay === day));
    const isReorderDayActive = Boolean(reorderDay && reorderDay === day);
    return (
      <div
        key={p.id}
        data-card-day={day}
        data-photo-id={p.id}
        draggable={false}
        className={`photo-card ${isSelected ? 'selected' : ''} ${isReorderSelected ? 'reorder-selected' : ''} ${isReorderDayActive ? 'reorder-day-active' : ''} ${isDragging ? 'dragging' : ''}`}
        style={{ flexBasis: `${aspectRatio * size}px`, height: `${size}px` }}
        onClick={(e) => handlePhotoClick(p, e)}
        onMouseDown={(e) => handleCardMouseDown(p, day, e)}
        onTouchStart={(e) => handleCardTouchStart(p, day, e)}
        onContextMenu={(e) => e.preventDefault()}
        onMouseEnter={() => p.contentType?.startsWith('video') && setHoveredVideoId(p.id)}
        onMouseLeave={() => setHoveredVideoId(null)}
      >
        {dropTarget?.targetId === p.id && (
          <div className={`drop-indicator-bar drop-indicator-${dropTarget.position}`} />
        )}
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
            <img src={p.url} loading="lazy" draggable={false} />
        )}
        {!isLightboxOpen && p.contentType?.startsWith('video') && hoveredVideoId !== p.id && <div className="video-badge">▶</div>}
        {handleUnlinkPerson && (
          <button
            type="button"
            className="unlink-person-btn"
            style={handleReorderPhotos ? { right: '42px' } : undefined}
            title={messages["unlinkPersonFromPhoto"]}
            onClick={(e) => { e.stopPropagation(); handleUnlinkPerson(p.id); }}
          >
            <Unlink2 size={14}/>
          </button>
        )}
        {/* Reorder selection button: click to check/uncheck assets to move together within this day;
            hold mouse or touch for 2s on an asset card to start dragging. Only rendered when a
            reorder handler is supplied and (once a day has checked assets) scoped to that day. */}
        {showReorderBtn && (
          <button
            type="button"
            className={`drag-handle ${isReorderSelected ? 'reorder-selected' : ''}`}
            aria-pressed={isReorderSelected}
            onMouseDown={(e) => e.stopPropagation()}
            onTouchStart={(e) => e.stopPropagation()}
            onContextMenu={(e) => e.preventDefault()}
            onClick={(e) => handleReorderButtonClick(p, day, e)}
            title={messages["dragToReorder"]}
          >
            <Move size={14} />
            {isReorderSelected && (
              <span className="drag-handle-check">
                <Check size={9} strokeWidth={3} />
              </span>
            )}
          </button>
        )}
        {badge}
      </div>
    );
  };

  const renderDisplayItem = (item, day, size = 220) => {
    if (item.members.length === 1) {
      return renderPhotoCard(item.primary, day, undefined, size);
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
    return renderPhotoCard(item.primary, day, badge, size);
  };

  const showTimelineScrubber = Boolean(dayIndex.days && dayIndex.days.length >= 2);

  return (
    <div
      className={`content-body ${showTimelineScrubber ? 'has-timeline-scrubber' : ''} ${dragState ? 'reorder-dragging-active' : ''}`}
      ref={gridContainerRef}
    >
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
              {dayItems.map(item => renderDisplayItem(item, day))}
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

      {dragState && (
        <div
          className="reorder-drag-preview"
          data-testid="reorder-drag-preview"
          style={{ left: `${dragState.clientX}px`, top: `${dragState.clientY}px` }}
        >
          {dragState.leadPhoto?.url && (
            <img src={dragState.leadPhoto.url} alt="" draggable={false} />
          )}
          <div className="reorder-drag-ready-badge">
            <Move size={12} />
          </div>
          {dragState.movedIds.length > 1 && (
            <div className="reorder-drag-count-badge">
              {dragState.movedIds.length}
            </div>
          )}
        </div>
      )}

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
