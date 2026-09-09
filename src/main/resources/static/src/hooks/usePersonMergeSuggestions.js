import { useState, useEffect, useCallback } from 'react';

// Library page banner (PersonMergeSuggestionBanner.jsx) — GET /api/faces/persons/merge-suggestions
// returns every live "these two people might be the same" suggestion for the current user's own
// space, most-similar first (see PersonMergeSuggestionService on the backend: an extra step run
// alongside face recognition, comparing existing persons directly against each other). Fetched
// once on mount, same pattern as useMemories.js/usePersons.js — this list only changes after a
// scheduled face-recognition run or an actual merge, neither of which happens while the Library
// page just sits open.
export function usePersonMergeSuggestions() {
  const [suggestions, setSuggestions] = useState([]);
  const [loading, setLoading] = useState(true);

  const fetchSuggestions = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch('/api/faces/persons/merge-suggestions');
      const data = await res.json();
      setSuggestions(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Failed to fetch person merge suggestions', err);
      setSuggestions([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchSuggestions();
  }, [fetchSuggestions]);

  // Dropped from local state the instant it's merged, rather than a full refetch — merging two
  // people doesn't change any OTHER pair's own similarity score, so nothing else in the list needs
  // to change.
  const removeSuggestion = useCallback((suggestionId) => {
    setSuggestions(prev => prev.filter(suggestion => suggestion.id !== suggestionId));
  }, []);

  return { suggestions, loading, fetchSuggestions, removeSuggestion };
}
