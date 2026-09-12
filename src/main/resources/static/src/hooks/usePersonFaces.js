import { useCallback, useEffect, useState } from 'react';

const PAGE_SIZE = 60;

// Paginated, distinct-faces view of one person - GET /api/faces/person/{id}/faces, sorted
// newest-first server-side (FaceController's own @PageableDefault(sort = "photo.dateTaken",
// direction = DESC)). Backs PersonDetail.jsx's "Faces" tab, the sibling of its existing
// day-indexed "Photos" tab (dayIndex prop, PhotoGrid) - this one lists the actual face crops
// instead of the photos they came from, and lets a face be unlinked directly from here.
export function usePersonFaces(personId) {
  const [faces, setFaces] = useState([]);
  const [page, setPage] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const fetchPage = useCallback(async (pageToLoad) => {
    if (!personId) return;
    setLoading(true);
    setError(null);
    try {
      const res = await fetch(`/api/faces/person/${personId}/faces?page=${pageToLoad}&size=${PAGE_SIZE}`);
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`);
      }
      const data = await res.json();
      const content = data.content || [];
      // Spring Data's own {content, page: {size, number, totalElements, totalPages}} envelope
      // (JsonConfiguration's pageSerializationMode = VIA_DTO) - no top-level `last`, unlike this
      // app's own PageResponse shape used elsewhere.
      const isLast = data.page ? data.page.number >= data.page.totalPages - 1 : data.last !== false;
      setFaces(prev => (pageToLoad === 0 ? content : [...prev, ...content]));
      setHasMore(!isLast);
    } catch (err) {
      console.error('Failed to fetch person faces', err);
      setError(err);
    } finally {
      setLoading(false);
    }
  }, [personId]);

  // Resets and refetches page 0 whenever the viewed person changes (navigating between people via
  // PersonStrip, say) - fetchPage is stable enough (only closes over personId) that including it
  // here wouldn't cause an extra run, but effects that reset local state should still only depend
  // on the thing that actually changed.
  useEffect(() => {
    setFaces([]);
    setPage(0);
    setHasMore(false);
    fetchPage(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [personId]);

  const loadMore = useCallback(() => {
    if (loading || !hasMore) return;
    const next = page + 1;
    setPage(next);
    fetchPage(next);
  }, [loading, hasMore, page, fetchPage]);

  // Unlinks a face from this person (DELETE /api/faces/{id}/person - detaches it, records a
  // FaceLinkExclusion so automatic re-matching never reattaches this exact pair, and flips the
  // face back to NEW so a later recognition run actually reconsiders it - see
  // FaceServiceImpl.detachFaceFromPerson). Removes it from the local list on success rather than
  // refetching - this person no longer has that face regardless of which loaded page it was on.
  // Throws on failure so the caller (PersonDetail.jsx) decides how to surface it.
  const unlinkFace = useCallback(async (faceId) => {
    const res = await fetch(`/api/faces/${faceId}/person`, { method: 'DELETE' });
    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`);
    }
    setFaces(prev => prev.filter(f => f.id !== faceId));
  }, []);

  return { faces, loading, hasMore, loadMore, unlinkFace, error };
}
