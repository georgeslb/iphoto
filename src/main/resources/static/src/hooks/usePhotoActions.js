import { useCallback } from 'react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

export function usePhotoActions(refreshAndMaintainScroll, setSelectedPhoto, handlePhotoClick, selectedIds, setSelectedIds) { // Added selectedIds, setSelectedIds as props
  const { messages } = useLocale(); // Use the useLocale hook
  // const [selectedIds, setSelectedIds] = useState([]); // Removed internal state

  const handleMoveToGarbage = useCallback(async (ids) => {
    if (!window.confirm(messages["trashConfirmation"](ids.length))) return;

    const res = await fetch('/api/photos/garbage', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) });
    if (res.ok) {
      setSelectedIds([]);
      await refreshAndMaintainScroll();
    } else {
      alert(messages["failedToMoveToGarbage"](res.status));
    }
  }, [refreshAndMaintainScroll, setSelectedIds, messages]);

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
    handleMoveToMain,
    handleRestore,
    handlePermanentDelete,
    handleUpdateDate,
    handleEditPhoto,
  };
}