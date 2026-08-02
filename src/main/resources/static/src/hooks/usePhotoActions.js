import { useCallback } from 'react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

export function usePhotoActions(refreshAndMaintainScroll, setSelectedPhoto, handleCloseLightbox, selectedIds, setSelectedIds) { // Added selectedIds, setSelectedIds as props
  const { messages } = useLocale(); // Use the useLocale hook
  // const [selectedIds, setSelectedIds] = useState([]); // Removed internal state

  const handleMoveToGarbage = useCallback(async (ids) => {
    if (!window.confirm(messages["trashConfirmation"](ids.length))) return;

    const res = await fetch('/api/photos/garbage', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) });
    if (res.ok) {
      setSelectedIds([]);
      await refreshAndMaintainScroll();
    }
  }, [refreshAndMaintainScroll, setSelectedIds, messages]); // Added messages to dependencies

  const handleRestore = useCallback(async (ids) => {
    const res = await fetch('/api/photos/garbage/restore', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) });
    if (res.ok) {
      setSelectedIds([]);
      await refreshAndMaintainScroll();
    }
  }, [refreshAndMaintainScroll, setSelectedIds]); // Added setSelectedIds to dependencies

  const handlePermanentDelete = useCallback(async (ids) => {
    if (!window.confirm(messages["permanentDeleteConfirmation"](ids.length))) return;
    const res = await fetch('/api/photos/garbage/permanent', { method: 'DELETE', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) });
    if (res.ok) {
      setSelectedIds([]);
      await refreshAndMaintainScroll();
    }
  }, [refreshAndMaintainScroll, setSelectedIds, messages]); // Added messages to dependencies

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
      console.log(editData);
      const res = await fetch(`/api/photos/${photoId}/edit`, {
        method: 'POST',
        body: editData,
      });
      if (res.ok) {
        const updatedPhoto = await res.json();
        if (!editData.saveAsNew) {
          setSelectedPhoto(updatedPhoto);
        } else {
          alert(messages["newPhotoProcessing"]);
          handleCloseLightbox();
        }
        await refreshAndMaintainScroll();
      } else {
        throw new Error(messages["failedToEditPhoto"](res.status));
      }
    } catch (err) {
      alert(messages["errorEditingPhoto"](err.message));
    }
  }, [refreshAndMaintainScroll, setSelectedPhoto, handleCloseLightbox, messages]);

  return {
    // selectedIds, // No longer returned as internal state
    // setSelectedIds, // No longer returned as internal state
    handleMoveToGarbage,
    handleRestore,
    handlePermanentDelete,
    handleUpdateDate,
    handleEditPhoto,
  };
}