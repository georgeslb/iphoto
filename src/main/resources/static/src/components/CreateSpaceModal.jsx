import React from 'react';

function CreateSpaceModal({
  show,
  onClose,
  newSpaceName,
  setNewSpaceName,
  newSpaceDescription,
  setNewSpaceDescription,
  spaceCreationError,
  handleCreateSpace
}) {
  if (!show) return null;

  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <h2>Create New Space</h2>
        {spaceCreationError && <div className="error-message">{spaceCreationError}</div>}
        <input type="text" placeholder="Space Name" value={newSpaceName} onChange={(e) => setNewSpaceName(e.target.value)} className="modal-input" />
        <textarea placeholder="Description (optional)" value={newSpaceDescription} onChange={(e) => setNewSpaceDescription(e.target.value)} className="modal-textarea" />
        <div className="modal-actions">
          <button className="btn" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" onClick={handleCreateSpace}>Create</button>
        </div>
      </div>
    </div>
  );
}

export default CreateSpaceModal;