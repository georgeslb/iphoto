import React from 'react';
import { X, Trash2 } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx';

// Shown when a "×N similar" badge (PhotoGrid.jsx) is clicked — every member of that one
// duplicate cluster, all at once (no pagination), instead of the old inline expand-in-place
// row. See TODO.md for why this replaced the inline version.
export default function DuplicateClusterModal({ item, onClose, handleMoveToGarbage }) {
  const { messages } = useLocale();

  if (!item) return null;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="duplicate-modal-content" onClick={(e) => e.stopPropagation()}>
        <div className="duplicate-modal-header">
          <span className="duplicate-modal-title">{messages["similarPhotosBadge"](item.members.length)}</span>
          <button type="button" className="duplicate-modal-close" title={messages["close"]} onClick={onClose}>
            <X size={20}/>
          </button>
        </div>
        <div className="duplicate-modal-grid">
          {item.members.map(m => (
            <div key={m.id} className="duplicate-modal-item">
              {/* The whole point of this popup is comparing near-duplicates closely enough to
                  decide which to keep — m.url (thumbnail) isn't sharp enough for that. Same
                  /original + cache-busting-by-fileHash pattern Lightbox.jsx uses for its own
                  full-size view. */}
              <img src={`/api/photos/${m.id}/original${m.fileHash ? `?v=${m.fileHash}` : ''}`} alt="" loading="lazy"/>
              {handleMoveToGarbage && (
                <button
                  type="button"
                  className="duplicate-remove-btn"
                  title={messages["moveToGarbage"]}
                  onClick={() => handleMoveToGarbage([m.id])}
                >
                  <Trash2 size={16}/>
                </button>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
