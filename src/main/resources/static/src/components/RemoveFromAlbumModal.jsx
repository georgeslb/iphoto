import React, { useState, useEffect } from 'react';
import { useLocale } from '../LocaleContext.jsx';
import AlbumSelectionList from './AlbumSelectionList';

function RemoveFromAlbumModal({
  show,
  onClose,
  photoId,
  albumsPhotoBelongsTo,
  handleRemovePhotosFromAlbum,
  fetchAlbumsForPhotos,
}) {
  const { messages } = useLocale();
  const [albumsToRemoveFrom, setAlbumsToRemoveFrom] = useState([]);
  const [validationError, setValidationError] = useState(null);

  useEffect(() => {
    if (show && photoId) {
      fetchAlbumsForPhotos([photoId]);
      setAlbumsToRemoveFrom([]);
      setValidationError(null);
    } else if (!show) {
      setAlbumsToRemoveFrom([]);
      setValidationError(null);
    }
  }, [show, photoId, fetchAlbumsForPhotos]);

  if (!show) return null;

  const handleToggleAlbumSelection = (albumId) => {
    setAlbumsToRemoveFrom(prev =>
      prev.includes(albumId) ? prev.filter(id => id !== albumId) : prev.concat(albumId)
    );
  };

  const handleConfirmRemove = async () => {
    if (albumsToRemoveFrom.length === 0) {
      setValidationError(messages["selectAtLeastOneAlbumToRemove"]);
      return;
    }
    await Promise.all(albumsToRemoveFrom.map(albumId =>
      handleRemovePhotosFromAlbum(albumId, [photoId])
    ));
    onClose();
  };

  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <h2>{messages["removeFromAlbumModalTitle"]}</h2>
        <p>{messages["removeFromAlbumModalDescription"]}</p>
        {validationError && <div className="error-message">{validationError}</div>}
        <AlbumSelectionList
          albums={albumsPhotoBelongsTo}
          selectedIds={albumsToRemoveFrom}
          onToggle={handleToggleAlbumSelection}
          variant="remove"
          emptyMessage={messages["photoNotInAnyAlbums"]}
        />
        <div className="modal-actions">
          <button className="btn" onClick={onClose}>{messages["cancel"]}</button>
          <button
            className="btn btn-primary"
            onClick={handleConfirmRemove}
            disabled={albumsToRemoveFrom.length === 0}
          >
            {messages["removeFromSelectedAlbumsButton"]}
          </button>
        </div>
      </div>
    </div>
  );
}

export default RemoveFromAlbumModal;
