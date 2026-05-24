import { useCallback } from 'react';

export function usePhotoActions(refreshAndMaintainScroll, setSelectedPhoto, handleCloseLightbox, selectedIds, setSelectedIds) { // Added selectedIds, setSelectedIds as props
  // const [selectedIds, setSelectedIds] = useState([]); // Removed internal state

  const handleMoveToGarbage = useCallback(async (ids) => {
    if (!window.confirm(`Trash ${ids.length} item(s)?`)) return;

    const res = await fetch('/api/photos/garbage', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) });
    if (res.ok) {
      setSelectedIds([]);
      await refreshAndMaintainScroll();
    }
  }, [refreshAndMaintainScroll, setSelectedIds]); // Added setSelectedIds to dependencies

  const handleRestore = useCallback(async (ids) => {
    const res = await fetch('/api/photos/garbage/restore', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) });
    if (res.ok) {
      setSelectedIds([]);
      await refreshAndMaintainScroll();
    }
  }, [refreshAndMaintainScroll, setSelectedIds]); // Added setSelectedIds to dependencies

  const handlePermanentDelete = useCallback(async (ids) => {
    if (!window.confirm(`Permanently delete ${ids.length} item(s)? This cannot be undone.`)) return;
    const res = await fetch('/api/photos/garbage/permanent', { method: 'DELETE', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) });
    if (res.ok) {
      setSelectedIds([]);
      await refreshAndMaintainScroll();
    }
  }, [refreshAndMaintainScroll, setSelectedIds]); // Added setSelectedIds to dependencies

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
        throw new Error(`Failed to update date: ${res.status}`);
      }
    } catch (err) {
      alert(`Error updating date: ${err.message}`);
    }
  }, [refreshAndMaintainScroll, setSelectedPhoto]);

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
          alert("New photo is being processed and will appear shortly!");
          handleCloseLightbox();
        }
        await refreshAndMaintainScroll();
      } else {
        throw new Error(`Failed to edit photo: ${res.status}`);
      }
    } catch (err) {
      alert(`Error editing photo: ${err.message}`);
    }
  }, [refreshAndMaintainScroll, setSelectedPhoto, handleCloseLightbox]);

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