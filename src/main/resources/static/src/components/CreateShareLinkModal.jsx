import React from 'react';

function CreateShareLinkModal({
  show,
  onClose,
  albumIdForShareLink,
  albums,
  newShareLinkName,
  setNewShareLinkName,
  newShareLinkPassword,
  setNewShareLinkPassword,
  newShareLinkExpiresInDays,
  setNewShareLinkExpiresInDays,
  shareLinkCreationError,
  handleCreateShareLink
}) {
  if (!show) return null;

  const currentAlbum = albums.find(a => a.id === albumIdForShareLink);

  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <h2>Create Share Link for "{currentAlbum?.name}"</h2>
        {shareLinkCreationError && <div className="error-message">{shareLinkCreationError}</div>}
        <input
            type="text"
            placeholder="Link Name (URL friendly)"
            value={newShareLinkName}
            onChange={(e) => setNewShareLinkName(e.target.value)}
            className="modal-input"
        />
        <input
            type="password"
            placeholder="Password (optional)"
            value={newShareLinkPassword}
            onChange={(e) => setNewShareLinkPassword(e.target.value)}
            className="modal-input"
            style={{ marginTop: '10px' }}
        />
        <input
            type="number"
            placeholder="Available for (days)"
            value={newShareLinkExpiresInDays}
            onChange={(e) => setNewShareLinkExpiresInDays(Math.max(1, parseInt(e.target.value) || 1))}
            className="modal-input"
            min="1"
            style={{ marginTop: '10px' }}
        />
        <div className="modal-actions">
          <button className="btn" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" onClick={handleCreateShareLink}>Create Link</button>
        </div>
      </div>
    </div>
  );
}

export default CreateShareLinkModal;