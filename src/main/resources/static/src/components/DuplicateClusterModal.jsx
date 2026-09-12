import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { X, Trash2, Star, Maximize2, Unlink2 } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx';

// Highest-resolution member wins the "suggested keep" star — the most common real-world
// duplicate case is an original plus a re-shared/re-compressed copy (messaging apps, social
// re-uploads), and those almost always shrink the resolution, so pixel count is a reliable,
// already-available (Photo.width/height) signal without needing a new backend field.
function pickKeeperIndex(members) {
  let bestIndex = 0;
  let bestPixels = -1;
  members.forEach((m, i) => {
    const pixels = (m.width || 0) * (m.height || 0);
    if (pixels > bestPixels) {
      bestPixels = pixels;
      bestIndex = i;
    }
  });
  return bestIndex;
}

// Shown when a "×N similar" badge (PhotoGrid.jsx) is clicked — every member of that one
// duplicate cluster. One member is "shown" full-size at a time (any of them — click any thumb
// in the side rail to jump straight to it); flicker/space/auto-flicker cycles the stage through
// every member in turn, not just two, so a cluster of 3+ is fully comparable this way too. See
// TODO.md.
export default function DuplicateClusterModal({ item, onClose, handleMoveToGarbage, handleUnlinkDuplicate, onExpand }) {
  const { messages } = useLocale();
  // useMemo (not `?? []` inline) so `members` keeps a stable reference across renders when
  // item?.members is nullish - otherwise the `?? []` fallback allocates a fresh empty array every
  // render, which would defeat keeperIndex's own useMemo below (a new [] each render never equals
  // the previous one, so it'd never actually skip recomputing).
  const members = useMemo(() => item?.members ?? [], [item?.members]);
  const keeperIndex = useMemo(() => pickKeeperIndex(members), [members]);

  const [shownIndex, setShownIndex] = useState(0);
  const [autoFlicker, setAutoFlicker] = useState(false);

  // Reset whenever a different cluster opens, or the current one changes shape (a member got
  // moved to garbage/unlinked from inside this same modal) — otherwise shownIndex could point
  // past the end of a just-shrunk members array.
  useEffect(() => {
    setShownIndex(keeperIndex);
    setAutoFlicker(false);
  }, [item?.primary?.id, members.length, keeperIndex]);

  const canFlicker = members.length > 1;

  // Cycles through every member in turn (wrapping around) — not just toggling back and forth
  // between two — so a cluster of any size is fully reachable from the stage itself, in addition
  // to jumping straight to one via the rail (selectMember, below).
  const toggleFlicker = useCallback(() => {
    setShownIndex(i => (i + 1) % members.length);
  }, [members.length]);

  const selectMember = useCallback((i) => setShownIndex(i), []);

  useEffect(() => {
    if (!autoFlicker || !canFlicker) return undefined;
    const id = setInterval(toggleFlicker, 700);
    return () => clearInterval(id);
  }, [autoFlicker, canFlicker, toggleFlicker]);

  useEffect(() => {
    if (!item || !canFlicker) return undefined;
    function onKeyDown(e) {
      if (e.code === 'Space' && !['INPUT', 'TEXTAREA'].includes(document.activeElement?.tagName)) {
        e.preventDefault();
        toggleFlicker();
      }
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [item, canFlicker, toggleFlicker]);

  if (!item) return null;

  const shown = members[shownIndex] ?? members[0];
  const shownIsKeeper = shownIndex === keeperIndex;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="duplicate-modal-content" onClick={(e) => e.stopPropagation()}>
        <div className="duplicate-modal-header">
          <span className="duplicate-modal-title">{messages["similarPhotosBadge"](members.length)}</span>
          <button type="button" className="duplicate-modal-close" title={messages["close"]} onClick={onClose}>
            <X size={20}/>
          </button>
        </div>

        <div className="duplicate-modal-body">
          <div className="duplicate-main-col">
            <div
              className="flicker-stage"
              tabIndex={0}
              role="button"
              aria-label={messages["duplicateFlickerToggleLabel"]}
              onClick={canFlicker ? toggleFlicker : undefined}
              style={canFlicker ? undefined : { cursor: 'default' }}
            >
              {/* Full-resolution /original (not the thumbnail url) — this stage is exactly
                  where a subtle crop/watermark/compression difference needs to be visible. */}
              <img
                src={`/api/photos/${shown.id}/original${shown.fileHash ? `?v=${shown.fileHash}` : ''}`}
                loading="lazy"
                alt=""
              />
              <span className={`flicker-readout ${shownIsKeeper ? '' : 'is-compare'}`}>
                {shownIsKeeper ? messages["duplicateShowingKeeper"] : messages["duplicateShowingCompare"]}
              </span>
              {onExpand && (
                <button
                  type="button"
                  className="flicker-icon-btn flicker-expand-btn"
                  title={messages["duplicateExpandTitle"]}
                  onClick={(e) => { e.stopPropagation(); onClose(); onExpand(shown); }}
                >
                  <Maximize2 size={15} />
                </button>
              )}
              <div className="flicker-photo-actions">
                {handleUnlinkDuplicate && (
                  <button
                    type="button"
                    className="flicker-icon-btn"
                    title={messages["duplicateUnlinkTitle"]}
                    onClick={(e) => { e.stopPropagation(); handleUnlinkDuplicate(shown.id, members.map(m => m.id)); }}
                  >
                    <Unlink2 size={15} />
                  </button>
                )}
                {handleMoveToGarbage && (
                  <button
                    type="button"
                    className="flicker-icon-btn flicker-icon-btn-danger"
                    title={messages["moveToGarbage"]}
                    onClick={(e) => { e.stopPropagation(); handleMoveToGarbage([shown.id]); }}
                  >
                    <Trash2 size={15} />
                  </button>
                )}
              </div>
            </div>

            {canFlicker && (
              <div className="flicker-controls">
                <button type="button" className="btn" onClick={toggleFlicker}>
                  {messages["duplicateToggleButton"]}
                </button>
                <label className="flicker-auto-toggle">
                  <input type="checkbox" checked={autoFlicker} onChange={(e) => setAutoFlicker(e.target.checked)} />
                  {messages["duplicateAutoFlickerLabel"]}
                </label>
              </div>
            )}

            <div className="duplicate-meta-panel">
              <div className="duplicate-meta-heading">
                {shownIsKeeper ? messages["duplicateKeeperLabel"] : messages["duplicateCompareLabel"]}
              </div>
              <table className="duplicate-meta-list">
                <tbody>
                  <tr>
                    <td className="duplicate-meta-label">{messages["duplicateResolutionLabel"]}</td>
                    <td>{shown.width && shown.height ? `${shown.width}×${shown.height}` : '—'}</td>
                  </tr>
                  <tr>
                    <td className="duplicate-meta-label">{messages["duplicateFileSizeLabel"]}</td>
                    <td>{shown.size || '—'}</td>
                  </tr>
                  <tr>
                    <td className="duplicate-meta-label">{messages["duplicateCameraLabel"]}</td>
                    <td>{shown.camera || '—'}</td>
                  </tr>
                  <tr>
                    <td className="duplicate-meta-label">{messages["dateTaken"]}</td>
                    <td>{shown.fullDateTime || '—'}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <div className="duplicate-chip-rail">
            {members.map((m, i) => {
              const isKeeper = i === keeperIndex;
              const isShown = i === shownIndex;
              return (
                <div
                  key={m.id}
                  className={`duplicate-rail-thumb ${isShown ? 'active' : ''}`}
                  title={isShown ? undefined : messages["duplicateShowMemberTitle"]}
                  onClick={() => selectMember(i)}
                >
                  <img src={m.url} loading="lazy" alt="" />
                  {isKeeper && (
                    <span className="rail-keeper-badge" title={messages["duplicateKeepBadge"]}>
                      <Star size={11} fill="currentColor" />
                    </span>
                  )}
                  {handleMoveToGarbage && (
                    <button
                      type="button"
                      className="duplicate-remove-btn rail-remove-btn"
                      title={messages["moveToGarbage"]}
                      onClick={(e) => { e.stopPropagation(); handleMoveToGarbage([m.id]); }}
                    >
                      <Trash2 size={12}/>
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
