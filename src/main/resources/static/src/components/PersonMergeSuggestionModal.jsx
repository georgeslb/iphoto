import React from 'react';
import { User } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx';

// One card in the suggestion pair — same shape as PersonMergePrompt.jsx's candidate cards (cover
// face or a placeholder, name or a fallback, photo count) but not selectable/clickable here: the
// pair IS the suggestion, there's nothing to toggle.
function SuggestionPersonCard({ person }) {
  const { messages } = useLocale();
  return (
    <div className="merge-suggestion-person">
      {person.coverPhoto?.image ? (
        <img src={person.coverPhoto.image} alt={person.name || messages["unknownPerson"]} />
      ) : (
        <div className="merge-suggestion-person-placeholder" role="img" aria-label={person.name || messages["unknownPerson"]}>
          <User size={28} />
        </div>
      )}
      <span className={`merge-suggestion-person-name ${!person.name ? 'person-name-placeholder' : ''}`}>
        {person.name || messages["whoIsThis"]}
      </span>
      <span className="merge-suggestion-person-count">{messages["photosCount"](person.photoCount || 0)}</span>
    </div>
  );
}

// Library page popup (PersonMergeSuggestionBanner.jsx): shows ONE "these two people might be the
// same" suggestion at a time. Three actions, per spec: Merge (the existing, exact same
// POST /api/faces/persons/merge two-person flow the Persons grid already uses — no new merge
// mechanism), Next (advance to the next suggestion without merging this one), and Close (dismiss
// the popup — does not delete/mark the suggestion, it'll show again next time the banner is
// opened, same as simply not having acted on it).
export default function PersonMergeSuggestionModal({ suggestion, index, total, onMerge, onNext, onClose, merging }) {
  const { messages } = useLocale();
  const similarityPercent = Math.round(suggestion.similarity * 100);

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content merge-suggestion-modal" onClick={(e) => e.stopPropagation()}>
        <h2>{messages["personMergeSuggestionModalTitle"]}</h2>
        <p>{messages["personMergeSuggestionCounter"](index + 1, total)}</p>
        <div className="merge-suggestion-pair">
          <SuggestionPersonCard person={suggestion.personA} />
          <span className="merge-suggestion-similarity">{messages["personMergeSuggestionSimilarity"](similarityPercent)}</span>
          <SuggestionPersonCard person={suggestion.personB} />
        </div>
        <div className="modal-actions">
          <button className="btn" onClick={onClose} disabled={merging}>{messages["close"]}</button>
          {total > 1 && (
            <button className="btn" onClick={onNext} disabled={merging}>{messages["nextSuggestionButton"]}</button>
          )}
          <button className="btn btn-primary" onClick={onMerge} disabled={merging}>
            {merging ? messages["mergingPersons"] : messages["mergePersonsButton"]}
          </button>
        </div>
      </div>
    </div>
  );
}
