import React from 'react';
import { Image as ImageIcon, Check } from 'lucide-react';

function AddToAlbumModal({
  show,
  onClose,
  albums,
  selectedIds,
  setSelectedIds,
  targetAlbumIds,
  setTargetAlbumIds,
  initialTargetAlbumIds, /* New prop */
  handleAddPhotosToAlbums
}) {
  if (!show) return null;

  const safeSelectedIds = Array.isArray(selectedIds) ? selectedIds : [];
  const safeTargetAlbumIds = Array.isArray(targetAlbumIds) ? targetAlbumIds : [];
  const safeInitialTargetAlbumIds = Array.isArray(initialTargetAlbumIds) ? initialTargetAlbumIds : [];

  const handleToggleAlbumSelection = (albumId) => {
    /* Prevent deselection of albums that were initially selected */
    if (safeInitialTargetAlbumIds.includes(albumId)) {
      return;
    }

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
        <h2>Add to Album</h2>
        <p>Select albums to add {safeSelectedIds.length} photo(s) to:</p>
        <div className="album-list-selection">
          {albums.length > 0 ? albums.map(album => {
            const isSelected = safeTargetAlbumIds.includes(album.id);
            const isInitiallySelected = safeInitialTargetAlbumIds.includes(album.id);

            return (
              <div
                key={album.id}
                className={`album-select-item ${isSelected ? 'selected' : ''} ${isInitiallySelected ? 'locked' : ''}`} /* Add 'locked' class */
                onClick={() => handleToggleAlbumSelection(album.id)}
              >
                <div className="album-cover-small">
                  {album.coverPhoto ? <img src={album.coverPhoto.url} alt={album.name} /> : <div className="album-placeholder-small"><ImageIcon size={24} color="#888" /></div>}
                </div>
                <div className="album-select-info">
                  <h3>{album.name}</h3>
                  <p>{album.photoCount} photos</p>
                </div>
                {isSelected && <div className="check-circle checked" style={{ marginLeft: 'auto', width: '22px', height: '22px' }}><Check size={14} /></div>}
              </div>
            );
          }) : <p>No albums available. Create one first!</p>}
        </div>
        <div className="modal-actions">
          <button className="btn" onClick={onClose}>Cancel</button>
          <button
            className="btn btn-primary"
            onClick={handleAddPhotosToAlbums}
            disabled={!hasNewAlbumsSelected} /* Disable if no new albums are selected */
          >
            Add to Albums
          </button>
        </div>
      </div>
    </div>
  );
}

export default AddToAlbumModal;