import React, { useState, useEffect } from 'react';
import { useLocale } from '../LocaleContext.jsx';

// Bulk "Edit Date Taken" - applies one exact date/time to every currently-selected photo. Modeled
// on AddToAlbumModal.jsx's shape (the closest existing multi-select modal): a description line
// naming the selection count, the actual control, then Cancel/Apply actions.
function EditDateModal({ show, onClose, selectedIds, handleBulkUpdateDate }) {
  const { messages } = useLocale();
  const [dateValue, setDateValue] = useState('');

  // Reset the picker every time the modal is (re)opened, so a stale value from a previous edit
  // never silently carries over to a new selection.
  useEffect(() => {
    if (show) {
      setDateValue('');
    }
  }, [show]);

  if (!show) return null;

  const safeSelectedIds = Array.isArray(selectedIds) ? selectedIds : [];

  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <h2>{messages["editDateModalTitle"]}</h2>
        <p>{messages["editDateModalDescription"](safeSelectedIds.length)}</p>
        <input
          type="datetime-local"
          value={dateValue}
          onChange={(e) => setDateValue(e.target.value)}
          className="form-control"
        />
        <div className="modal-actions">
          <button className="btn" onClick={onClose}>{messages["cancel"]}</button>
          <button
            className="btn btn-primary"
            onClick={() => handleBulkUpdateDate(safeSelectedIds, dateValue)}
            disabled={!dateValue}
          >
            {messages["applyToNPhotos"](safeSelectedIds.length)}
          </button>
        </div>
      </div>
    </div>
  );
}

export default EditDateModal;
