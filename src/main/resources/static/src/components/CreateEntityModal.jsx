import React from 'react';
import { useLocale } from '../LocaleContext.jsx';

function CreateEntityModal({
  show,
  onClose,
  title,
  name,
  setName,
  namePlaceholder,
  description,
  setDescription,
  descriptionPlaceholder,
  error,
  onSubmit,
}) {
  const { messages } = useLocale();
  if (!show) return null;

  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <h2>{title}</h2>
        {error && <div className="error-message">{error}</div>}
        <input type="text" placeholder={namePlaceholder} value={name} onChange={(e) => setName(e.target.value)} className="modal-input" />
        <textarea placeholder={descriptionPlaceholder} value={description} onChange={(e) => setDescription(e.target.value)} className="modal-textarea" />
        <div className="modal-actions">
          <button className="btn" onClick={onClose}>{messages["cancel"]}</button>
          <button className="btn btn-primary" onClick={onSubmit}>{messages["createButton"]}</button>
        </div>
      </div>
    </div>
  );
}

export default CreateEntityModal;
