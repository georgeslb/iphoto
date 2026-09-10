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
  const { count, countLoaded, current, activeIndex, loading, openAt, goToNext, refreshAfterMerge } = usePersonMergeSuggestions();
  const [showModal, setShowModal] = useState(false);
  const [merging, setMerging] = useState(false);

  if (!countLoaded || count === 0) return null;

  const handleReview = () => {
    setShowModal(true);
    openAt(0);
  };

  const handleMerge = async () => {
    if (!current) return;
    setMerging(true);
    try {
      const response = await fetch('/api/faces/persons/merge', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ personIds: [current.personA.id, current.personB.id] }),
      });
      if (!response.ok) {
        throw new Error(messages["failedToMergePersons"]);
      }
      // Re-fetches both the count and the (now possibly different) suggestion at this same index -
      // the whole banner/modal unmounts on its own, via the count === 0 guard above, once nothing
      // is left to suggest.
      await refreshAfterMerge();
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
        <span>{messages["personMergeSuggestionBannerText"](count)}</span>
        <button type="button" className="btn btn-primary" onClick={handleReview} disabled={loading}>
          {messages["reviewSuggestionsButton"]}
        </button>
      </div>
      {showModal && current && (
        <PersonMergeSuggestionModal
          suggestion={current}
          index={activeIndex}
          total={count}
          onMerge={handleMerge}
          onNext={goToNext}
          onClose={() => setShowModal(false)}
          merging={merging || loading}
        />
      )}
    </>
  );
}
