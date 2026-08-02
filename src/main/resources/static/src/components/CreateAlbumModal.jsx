import React from 'react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

function CreateAlbumModal({
  show,
  onClose,
  newAlbumName,
  setNewAlbumName,
  newAlbumDescription,
  setNewAlbumDescription,
  albumCreationError,
  handleCreateAlbum
}) {
  const { messages } = useLocale(); // Use the useLocale hook
  if (!show) return null;

  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <h2>{messages["createNewAlbumTitle"]}</h2>
        {albumCreationError && <div className="error-message">{albumCreationError}</div>}
        <input type="text" placeholder={messages["albumNamePlaceholder"]} value={newAlbumName} onChange={(e) => setNewAlbumName(e.target.value)} className="modal-input" />
        <textarea placeholder={messages["albumDescriptionPlaceholder"]} value={newAlbumDescription} onChange={(e) => setNewAlbumDescription(e.target.value)} className="modal-textarea" />
        <div className="modal-actions">
          <button className="btn" onClick={onClose}>{messages["cancel"]}</button>
          <button className="btn btn-primary" onClick={handleCreateAlbum}>{messages["createButton"]}</button>
        </div>
      </div>
    </div>
  );
}

export default CreateAlbumModal;