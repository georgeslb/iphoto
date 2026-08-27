import React from 'react';
import { Check, User } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx';

// Shown when a whole-person rename (usePersonRename.js) collides with one or more existing
// people already sharing the new name — one card per candidate, each showing its own cover face
// so the user can tell them apart, with a checkbox to pick which (if any) to merge into the
// person being renamed. Confirming with zero selected still applies the rename, just without
// merging — a deliberate choice, not disabled, since the app doesn't enforce name uniqueness.
export default function PersonMergePrompt({ name, candidates, selectedIds, onToggle, onCancel, onConfirm, submitting }) {
  const { messages } = useLocale();

  return (
    <div className="modal-overlay" onClick={onCancel}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <h2>{messages["personRenameCollisionTitle"]}</h2>
        <p>{messages["personRenameCollisionPrompt"](name)}</p>
        <div className="merge-candidate-list">
          {candidates.map(candidate => {
            const isSelected = selectedIds.includes(candidate.id);
            return (
              <div
                key={candidate.id}
                className={`merge-candidate-item ${isSelected ? 'selected' : ''}`}
                onClick={() => onToggle(candidate.id)}
              >
                <div className={`check-circle ${isSelected ? 'checked' : ''}`}>
                  {isSelected && <Check size={14} />}
                </div>
                {candidate.coverPhoto?.image ? (
                  <img src={candidate.coverPhoto.image} alt={candidate.name || messages["unknownPerson"]} />
                ) : (
                  <div className="merge-candidate-placeholder" role="img" aria-label={candidate.name || messages["unknownPerson"]}>
                    <User size={24} />
                  </div>
                )}
                <span>{messages["photosCount"](candidate.photoCount || 0)}</span>
              </div>
            );
          })}
        </div>
        <div className="modal-actions">
          <button className="btn" onClick={onCancel} disabled={submitting}>{messages["cancel"]}</button>
          <button className="btn btn-primary" onClick={onConfirm} disabled={submitting}>
            {submitting
              ? messages["mergingPersons"]
              : (selectedIds.length > 0 ? messages["confirmMergeButton"] : messages["renameWithoutMergingButton"])}
          </button>
        </div>
      </div>
    </div>
  );
}
