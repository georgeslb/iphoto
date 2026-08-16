import React from 'react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale
import AlbumSelectionList from './AlbumSelectionList';

function AddToAlbumModal({
  show,
  onClose,
  albums,
  selectedIds,
  targetAlbumIds,
  setTargetAlbumIds,
  initialTargetAlbumIds, /* New prop */
  handleAddPhotosToAlbums
}) {
  const { messages } = useLocale(); // Use the useLocale hook
  if (!show) return null;

  const safeSelectedIds = Array.isArray(selectedIds) ? selectedIds : [];
  const safeTargetAlbumIds = Array.isArray(targetAlbumIds) ? targetAlbumIds : [];
  const safeInitialTargetAlbumIds = Array.isArray(initialTargetAlbumIds) ? initialTargetAlbumIds : [];

  const handleToggleAlbumSelection = (albumId) => {
    setTargetAlbumIds(prev => {
      const currentAlbums = Array.isArray(prev) ? prev : [];
      return currentAlbums.includes(albumId)
        ? currentAlbums.filter(id => id !== albumId)
        : currentAlbums.concat(albumId);
    });
  };

  /* Determine if the "Add to Albums" button should be enabled */
  /* It should be enabled if there's at least one album in targetAlbumIds that wasn't in initialTargetAlbumIds */
  const hasNewAlbumsSelected = safeTargetAlbumIds.some(albumId => !safeInitialTargetAlbumIds.includes(albumId));

  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <h2>{messages["addToAlbumModalTitle"]}</h2>
        <p>{messages["addToAlbumModalDescription"](safeSelectedIds.length)}</p>
        <AlbumSelectionList
          albums={albums}
          selectedIds={safeTargetAlbumIds}
          lockedIds={safeInitialTargetAlbumIds}
          onToggle={handleToggleAlbumSelection}
          variant="add"
          emptyMessage={messages["noAlbumsAvailable"]}
        />
        <div className="modal-actions">
          <button className="btn" onClick={onClose}>{messages["cancel"]}</button>
          <button
            className="btn btn-primary"
            onClick={handleAddPhotosToAlbums}
            disabled={!hasNewAlbumsSelected} /* Disable if no new albums are selected */
          >
            {messages["addToAlbumsButton"]}
          </button>
        </div>
      </div>
    </div>
  );
}

export default AddToAlbumModal;
