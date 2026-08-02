import React from 'react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

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
  const { messages } = useLocale(); // Use the useLocale hook
  if (!show) return null;

  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <h2>{messages["createNewSpaceTitle"]}</h2>
        {spaceCreationError && <div className="error-message">{spaceCreationError}</div>}
        <input type="text" placeholder={messages["spaceNamePlaceholder"]} value={newSpaceName} onChange={(e) => setNewSpaceName(e.target.value)} className="modal-input" />
        <textarea placeholder={messages["spaceDescriptionPlaceholder"]} value={newSpaceDescription} onChange={(e) => setNewSpaceDescription(e.target.value)} className="modal-textarea" />
        <div className="modal-actions">
          <button className="btn" onClick={onClose}>{messages["cancel"]}</button>
          <button className="btn btn-primary" onClick={handleCreateSpace}>{messages["createButton"]}</button>
        </div>
      </div>
    </div>
  );
}

export default CreateSpaceModal;