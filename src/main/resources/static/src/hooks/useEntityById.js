import { useEffect, useState } from 'react';

export function useEntityById(id, endpoint) {
  const [entity, setEntity] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    setError(null);
    fetch(`${endpoint}/${id}`)
      .then(response => {
        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }
        return response.json();
      })
      .then(data => {
        setEntity(data);
        setLoading(false);
      })
      .catch(err => {
        setError(err);
        setLoading(false);
      });
  }, [id, endpoint]);

  // setEntity is exposed for callers that mutate the entity server-side themselves (e.g.
  // PersonDetail.jsx renaming/merging via usePersonRename) and want to reflect the result
  // immediately without waiting on a full refetch — the effect above only (re)fetches when
  // `id`/`endpoint` change, not on every render, so it won't clobber a manual set like this.
  return { entity, setEntity, loading, error };
}
