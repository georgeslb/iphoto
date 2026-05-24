import React from 'react';

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
  if (!show) return null;

  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <h2>Create New Album</h2>
        {albumCreationError && <div className="error-message">{albumCreationError}</div>}
        <input type="text" placeholder="Album Name" value={newAlbumName} onChange={(e) => setNewAlbumName(e.target.value)} className="modal-input" />
        <textarea placeholder="Description (optional)" value={newAlbumDescription} onChange={(e) => setNewAlbumDescription(e.target.value)} className="modal-textarea" />
        <div className="modal-actions">
          <button className="btn" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" onClick={handleCreateAlbum}>Create</button>
        </div>
      </div>
    </div>
  );
}

export default CreateAlbumModal;