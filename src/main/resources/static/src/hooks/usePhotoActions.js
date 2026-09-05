import { useCallback } from 'react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

export function usePhotoActions(refreshAndMaintainScroll, setSelectedPhoto, handlePhotoClick, selectedIds, setSelectedIds, removePhotosLocally, fetchAlbums, fetchSpaces) { // Added selectedIds, setSelectedIds as props
  const { messages } = useLocale(); // Use the useLocale hook
  // const [selectedIds, setSelectedIds] = useState([]); // Removed internal state

  const handleMoveToGarbage = useCallback(async (ids) => {
    if (!window.confirm(messages["trashConfirmation"](ids.length))) return;

    const res = await fetch('/api/photos/garbage', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) });
    if (res.ok) {
      setSelectedIds([]);
      // Deliberately NOT refreshAndMaintainScroll here: that resets the grid to page 0 only,
      // silently dropping every page loaded past the first and then trying to restore the old
      // scrollTop pixel value against much shorter content - it both yanks the user's scroll
      // position and, when triggered from DuplicateClusterModal (whose visible cluster is
      // derived live from `photos`, see PhotoGrid.jsx), can wipe the open cluster's primary
      // out of the reset list and auto-close the modal even though the cluster itself still
      // has other members left. Instead, remove exactly the garbage'd ids from whatever's
      // already loaded, in place - App.jsx's removePhotosLocally knows whether the current view
      // is the flat usePhotoData feed (Garbage/Person) or the day-bucketed one (Library/Album/
      // Space) and updates the right shape; either way it also scrubs the ids out of every
      // remaining photo's own `duplicates` list, since that's what PhotoGrid.jsx's cluster
      // grouping actually reads members from, not a re-derivation off the top-level list.
      removePhotosLocally(ids);
      await fetchAlbums();
      await fetchSpaces();
    } else {
      alert(messages["failedToMoveToGarbage"](res.status));
    }
  }, [removePhotosLocally, fetchAlbums, fetchSpaces, setSelectedIds, messages]);

  // Removes one photo from its duplicate cluster without touching the photo itself — distinct
  // from handleMoveToGarbage, which deletes/trashes it. `groupPhotoIds` is every member of that
  // cluster as currently known client-side (DuplicateController.unlinkPhoto uses it to confirm
  // the group hasn't changed server-side since). Used by DuplicateClusterModal.jsx's unlink icon
  // ("this isn't actually a duplicate of the rest of this group").
  const handleUnlinkDuplicate = useCallback(async (photoId, groupPhotoIds) => {
    const res = await fetch('/api/photos/duplicates/unlink', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ unlinkedPhotoId: photoId, duplicatesId: groupPhotoIds }),
    });
    if (res.ok) {
      await refreshAndMaintainScroll();
    } else {
      alert(messages["failedToUnlinkDuplicate"](res.status));
    }
  }, [refreshAndMaintainScroll, messages]);

  const handleRestore = useCallback(async (ids) => {
    const res = await fetch('/api/photos/garbage/restore', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) });
    if (res.ok) {
      setSelectedIds([]);
      await refreshAndMaintainScroll();
    } else {
      alert(messages["failedToRestore"](res.status));
    }
  }, [refreshAndMaintainScroll, setSelectedIds, messages]);

  const handleMoveToMain = useCallback(async (ids) => {
    const res = await fetch('/api/photos/main', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) });
    if (res.ok) {
      setSelectedIds([]);
      await refreshAndMaintainScroll();
    } else {
      alert(messages["failedToMoveToMain"](res.status));
    }
  }, [refreshAndMaintainScroll, setSelectedIds, messages]);

  const handlePermanentDelete = useCallback(async (ids) => {
    if (!window.confirm(messages["permanentDeleteConfirmation"](ids.length))) return;
    const res = await fetch('/api/photos/garbage/permanent', { method: 'DELETE', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) });
    if (res.ok) {
      setSelectedIds([]);
      await refreshAndMaintainScroll();
    } else {
      alert(messages["failedToPermanentlyDelete"](res.status));
    }
  }, [refreshAndMaintainScroll, setSelectedIds, messages]);

  const handleUpdateDate = useCallback(async (photoId, newDateValue) => {
    try {
      const res = await fetch(`/api/photos/${photoId}/date`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ newDate: newDateValue })
      });
      if (res.ok) {
        const updatedPhoto = await res.json();
        setSelectedPhoto(updatedPhoto);
        await refreshAndMaintainScroll();
      } else {
        throw new Error(messages["failedToUpdateDate"](res.status));
      }
    } catch (err) {
      alert(messages["errorUpdatingDate"](err.message));
    }
  }, [refreshAndMaintainScroll, setSelectedPhoto, messages]);

  const handleEditPhoto = useCallback(async (photoId, editData) => {
    try {
      const res = await fetch(`/api/photos/${photoId}/edit`, {
        method: 'POST',
        body: editData,
      });
      if (res.ok) {
        const updatedPhoto = await res.json();
        // Both "Save" (same id, overwritten file) and "Save New" (freshly
        // created asset) return the resulting photo's id in the response body;
        // refresh the grid, then land the lightbox on that asset by id.
        // Use handlePhotoClick (not the raw setter) so the URL (/asset/:id or
        // /albums/:albumId/asset/:id) is updated too — otherwise "Save New" would
        // leave the address bar pointing at the old photo's id.
        await refreshAndMaintainScroll();
        handlePhotoClick(updatedPhoto);
      } else {
        throw new Error(messages["failedToEditPhoto"](res.status));
      }
    } catch (err) {
      alert(messages["errorEditingPhoto"](err.message));
    }
  }, [refreshAndMaintainScroll, handlePhotoClick, messages]);

  return {
    // selectedIds, // No longer returned as internal state
    // setSelectedIds, // No longer returned as internal state
    handleMoveToGarbage,
    handleUnlinkDuplicate,
    handleMoveToMain,
    handleRestore,
    handlePermanentDelete,
    handleUpdateDate,
    handleEditPhoto,
  };
}