import React, { useState } from 'react';
import { useLocale } from '../LocaleContext.jsx';
import { usePersonMergeSuggestions } from '../hooks/usePersonMergeSuggestions';
import PersonMergeSuggestionModal from './PersonMergeSuggestionModal';

// Library-only notice (see App.jsx, rendered alongside MemoriesBar right below the header) that
// two or more existing people might actually be the same real person — backed by an extra step
// run alongside face recognition (PersonMergeSuggestionService), not computed here. Clicking
// "Review" opens a popup showing one pair at a time; merging reuses the exact same
// POST /api/faces/persons/merge two-person flow the Persons grid's own merge action already uses,
// so there's only ever one merge mechanism in the app, not two.
export default function PersonMergeSuggestionBanner() {
  const { messages } = useLocale();
  const { suggestions, loading, removeSuggestion } = usePersonMergeSuggestions();
  const [showModal, setShowModal] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const [merging, setMerging] = useState(false);

  if (loading || suggestions.length === 0) return null;

  const currentIndex = activeIndex % suggestions.length;
  const currentSuggestion = suggestions[currentIndex];

  const handleNext = () => {
    setActiveIndex(i => (i + 1) % suggestions.length);
  };

  const handleMerge = async () => {
    setMerging(true);
    try {
      const response = await fetch('/api/faces/persons/merge', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ personIds: [currentSuggestion.personA.id, currentSuggestion.personB.id] }),
      });
      if (!response.ok) {
        throw new Error(messages["failedToMergePersons"]);
      }
      // No need to adjust activeIndex here - currentIndex is always `activeIndex % suggestions
      // .length`, recomputed every render, so it naturally wraps into range against the shorter
      // list on the next render (or the whole banner/modal unmounts if that was the last one, via
      // the same suggestions.length === 0 guard above).
      removeSuggestion(currentSuggestion.id);
    } catch (error) {
      console.error(messages["failedToMergePersons"], error);
      alert(messages["failedToMergePersons"]);
    } finally {
      setMerging(false);
    }
  };

  return (
    <>
      <div className="person-merge-suggestion-banner">
        <span>{messages["personMergeSuggestionBannerText"](suggestions.length)}</span>
        <button type="button" className="btn btn-primary" onClick={() => { setActiveIndex(0); setShowModal(true); }}>
          {messages["reviewSuggestionsButton"]}
        </button>
      </div>
      {showModal && suggestions.length > 0 && (
        <PersonMergeSuggestionModal
          suggestion={currentSuggestion}
          index={currentIndex}
          total={suggestions.length}
          onMerge={handleMerge}
          onNext={handleNext}
          onClose={() => setShowModal(false)}
          merging={merging}
        />
      )}
    </>
  );
}
