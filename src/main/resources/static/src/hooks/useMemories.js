import { useState, useEffect, useCallback } from 'react';

// Library's "N months/years ago" bar - GET /api/photos/memories returns one entry per configured
// offset (IPhotoProperties.Memories.monthsAgo on the backend) that actually has a photo, already
// sorted however the backend returns them (most-recent offset first). Fetched once on mount -
// today's date only changes once a day, so there's no need to refetch on every render.
export function useMemories() {
  const [memories, setMemories] = useState([]);
  const [loading, setLoading] = useState(true);

  const fetchMemories = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch('/api/photos/memories');
      const data = await res.json();
      setMemories(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Failed to fetch memories', err);
      setMemories([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchMemories();
  }, [fetchMemories]);

  return { memories, loading };
}
