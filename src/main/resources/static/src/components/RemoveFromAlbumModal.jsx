import React, { useState, useEffect } from 'react';
import { Image as ImageIcon, Check, X } from 'lucide-react';

function RemoveFromAlbumModal({
  show,
  onClose,
  photoId,
  albumsPhotoBelongsTo,
  handleRemovePhotosFromAlbum,
  fetchAlbumsForPhotos,
}) {
  const [albumsToRemoveFrom, setAlbumsToRemoveFrom] = useState([]);

  useEffect(() => {
    if (show && photoId) {
      fetchAlbumsForPhotos([photoId]);
      setAlbumsToRemoveFrom([]);
    } else if (!show) {
      setAlbumsToRemoveFrom([]);
    }
  }, [show, photoId, fetchAlbumsForPhotos]);

  if (!show) return null;

  const handleToggleAlbumSelection = (albumId) => {
    setAlbumsToRemoveFrom(prev => {
      return prev.includes(albumId)
        ? prev.filter(id => id !== albumId)
        : prev.concat(albumId);
    });
  };

  const handleConfirmRemove = async () => {
    if (albumsToRemoveFrom.length === 0) {
      alert("Please select at least one album to remove the photo from.");
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
        <h2>Remove from Album</h2>
        <p>Select albums to remove this photo from:</p>
        <div className="album-list-selection">
          {albumsPhotoBelongsTo.length > 0 ? albumsPhotoBelongsTo.map(album => {
            const isSelectedForRemoval = albumsToRemoveFrom.includes(album.id);
            return (
              <div
                key={album.id}
                className={`album-select-item ${isSelectedForRemoval ? 'remove-selected' : ''}`}
                onClick={() => handleToggleAlbumSelection(album.id)}
              >
                <div className="album-cover-small">
                  {album.coverPhoto ? <img src={album.coverPhoto.url} alt={album.name} /> : <div className="album-placeholder-small"><ImageIcon size={24} color="#888" /></div>}
                </div>
                <div className="album-select-info">
                  <h3>{album.name}</h3>
                  <p>{album.photoCount} photos</p>
                </div>
                {isSelectedForRemoval && <div className="check-circle checked" style={{ marginLeft: 'auto', width: '22px', height: '22px', backgroundColor: '#ff3b30', borderColor: '#ff3b30' }}><X size={14} /></div>}
              </div>
            );
          }) : <p>This photo does not belong to any albums.</p>}
        </div>
        <div className="modal-actions">
          <button className="btn" onClick={onClose}>Cancel</button>
          <button
            className="btn btn-primary"
            onClick={handleConfirmRemove}
            disabled={albumsToRemoveFrom.length === 0}
          >
            Remove from Selected Albums
          </button>
        </div>
      </div>
    </div>
  );
}

export default RemoveFromAlbumModal;