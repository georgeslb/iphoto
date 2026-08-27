import { useState, useCallback } from 'react';

// Shared rename-with-merge-collision flow for whole-person renames (Persons.jsx's inline rename,
// PersonDetail.jsx's page-title rename) — distinct from Lightbox.jsx's single-face rename, which
// has its own simpler single-candidate version of this same pattern.
//
// First attempt (`rename`) PUTs {name, confirmed: false}. If the backend finds one or more OTHER
// people already named that, it comes back 409 with their DTOs — surfaced here as
// `collisionCandidates` so the caller can render a picker (see PersonMergePrompt.jsx). Confirming
// (`confirmRename`) retries with {confirmed: true, mergeWithPersonIds: <whatever was selected>} —
// possibly none, which is a deliberate "rename anyway, don't merge with any of these" choice, not
// an error. `onRenamed(updatedPerson)` fires on every successful apply (plain rename or merged).
export function usePersonRename(onRenamed) {
  const [collisionCandidates, setCollisionCandidates] = useState(null); // null = no prompt open
  const [pendingPersonId, setPendingPersonId] = useState(null);
  const [pendingName, setPendingName] = useState('');
  const [selectedMergeIds, setSelectedMergeIds] = useState([]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);

  const submit = useCallback(async (personId, name, confirmed, mergeWithPersonIds) => {
    const res = await fetch(`/api/faces/person/${personId}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, confirmed, mergeWithPersonIds }),
    });

    if (res.status === 409) {
      const candidates = await res.json();
      setPendingPersonId(personId);
      setPendingName(name);
      setSelectedMergeIds([]);
      setCollisionCandidates(candidates);
      return;
    }
    if (!res.ok) {
      throw new Error(`renamePerson failed: ${res.status}`);
    }
    const updated = await res.json();
    setCollisionCandidates(null);
    if (onRenamed) onRenamed(updated);
  }, [onRenamed]);

  const rename = useCallback(async (personId, name) => {
    setError(null);
    try {
      await submit(personId, name, false, []);
    } catch (err) {
      setError(err);
    }
  }, [submit]);

  const toggleMergeCandidate = useCallback((id) => {
    setSelectedMergeIds(prev => (prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]));
  }, []);

  const confirmRename = useCallback(async () => {
    setSubmitting(true);
    setError(null);
    try {
      await submit(pendingPersonId, pendingName, true, selectedMergeIds);
    } catch (err) {
      setError(err);
    } finally {
      setSubmitting(false);
    }
  }, [submit, pendingPersonId, pendingName, selectedMergeIds]);

  // Aborts the whole rename attempt — the name is NOT changed, unlike confirmRename with zero
  // candidates selected (which still applies the rename, just without merging).
  const cancelRename = useCallback(() => {
    setCollisionCandidates(null);
    setPendingPersonId(null);
    setSelectedMergeIds([]);
  }, []);

  return {
    rename,
    collisionCandidates,
    pendingName,
    selectedMergeIds,
    toggleMergeCandidate,
    confirmRename,
    cancelRename,
    submitting,
    error,
  };
}
