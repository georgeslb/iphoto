import { useCallback, useState } from 'react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

export function usePhotoActions(refreshAndMaintainScroll, setSelectedPhoto, handlePhotoClick, selectedIds, setSelectedIds, removePhotosLocally, fetchAlbums, fetchSpaces) { // Added selectedIds, setSelectedIds as props
  const { messages } = useLocale(); // Use the useLocale hook
  // const [selectedIds, setSelectedIds] = useState([]); // Removed internal state
  const [showEditDateModal, setShowEditDateModal] = useState(false);

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

  // Bulk "Edit Date Taken" (Header.jsx's canEditDate button + EditDateModal.jsx) - applies one
  // exact timestamp to every selected photo. Deliberately reuses the existing single-photo
  // PATCH /api/photos/{id}/date endpoint once per id via Promise.all, rather than a new bulk
  // endpoint - the same pattern useAlbumManagement.js#handleAddPhotosToAlbums already uses for
  // its own bulk action (one album-add call per target album).
  const handleBulkUpdateDate = useCallback(async (photoIds, newDateValue) => {
    if (!photoIds || photoIds.length === 0 || !newDateValue) return;

    const updatePromises = photoIds.map(photoId =>
        fetch(`/api/photos/${photoId}/date`, {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ newDate: newDateValue }),
        })
    );

    try {
      const responses = await Promise.all(updatePromises);
      if (responses.every(res => res.ok)) {
        setSelectedIds([]);
        setShowEditDateModal(false);
        await refreshAndMaintainScroll();
      } else {
        throw new Error(messages["failedToUpdateDate"](responses.find(res => !res.ok).status));
      }
    } catch (err) {
      alert(messages["errorUpdatingDate"](err.message));
    }
  }, [refreshAndMaintainScroll, setSelectedIds, messages]);

  // Bulk "Download" (Header.jsx's canDownload button) - a single selected photo downloads
  // directly via the browser (no fetch needed, same as clicking any plain download link);
  // multiple selected photos download as one zip, streamed back from a POST (see
  // PhotoController#downloadMultiple - POST, not GET+query-string, since a large selection could
  // exceed a comfortable URL length).
  const handleDownload = useCallback(async (photoIds) => {
    if (!photoIds || photoIds.length === 0) return;

    if (photoIds.length === 1) {
      window.location.href = `/api/photos/${photoIds[0]}/download`;
      return;
    }

    try {
      const res = await fetch('/api/photos/download', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(photoIds),
      });
      if (!res.ok) throw new Error(messages["failedToDownloadPhotos"](res.status));

      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = 'photos.zip';
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch (err) {
      alert(messages["errorDownloadingPhotos"](err.message));
    }
  }, [messages]);

  // Drag-to-reorder within a day (PhotoGrid.jsx's drag handle) - `updates` is
  // [{photoId, newDate}, ...] from utils/photoReorder.js#computeReorderDates, each entry already
  // carrying its own precomputed timestamp. Same one-PATCH-per-id-via-Promise.all pattern as
  // handleBulkUpdateDate above, just a per-photo date instead of one shared value. Deliberately
  // does NOT clear selectedIds on success (unlike handleBulkUpdateDate) - reordering shouldn't drop
  // the user's active multi-selection.
  const handleReorderPhotos = useCallback(async (updates) => {
    if (!updates || updates.length === 0) return;

    const updatePromises = updates.map(({ photoId, newDate }) =>
        fetch(`/api/photos/${photoId}/date`, {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ newDate }),
        })
    );

    try {
      const responses = await Promise.all(updatePromises);
      if (responses.every(res => res.ok)) {
        await refreshAndMaintainScroll();
      } else {
        throw new Error(messages["failedToUpdateDate"](responses.find(res => !res.ok).status));
      }
    } catch (err) {
      alert(messages["errorUpdatingDate"](err.message));
    }
  }, [refreshAndMaintainScroll, messages]);

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
    showEditDateModal,
    setShowEditDateModal,
    handleBulkUpdateDate,
    handleDownload,
    handleReorderPhotos,
  };
}