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
  handleAddPhotosToAlbums
}) {
  if (!show) return null;

  // Ensure selectedIds is an array before accessing .length
  const safeSelectedIds = Array.isArray(selectedIds) ? selectedIds : [];
  // Ensure targetAlbumIds is an array before accessing .length
  const safeTargetAlbumIds = Array.isArray(targetAlbumIds) ? targetAlbumIds : [];

  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <h2>Add to Album</h2>
        {/* Use safeSelectedIds to safely access length */}
        <p>Select albums to add {safeSelectedIds.length} photo(s) to:</p>
        <div className="album-list-selection">
          {albums.length > 0 ? albums.map(album => (
                <div
                    key={album.id}
                    className={`album-select-item ${safeTargetAlbumIds.includes(album.id) ? 'selected' : ''}`}
                    onClick={() => {
                      setTargetAlbumIds(prev => {
                            let currentAlbums = prev;
                            if (currentAlbums === undefined || currentAlbums === null) {
                                currentAlbums = [];
                            } else if (!Array.isArray(currentAlbums)) {
                                currentAlbums = Array.from(currentAlbums);
                            }

                            return currentAlbums.includes(album.id)
                                ? currentAlbums.filter(id => id !== album.id)
                                : currentAlbums.concat(album.id);
                          }
                      );
                    }}
                >
                  <div className="album-cover-small">
                    {album.coverPhoto ? <img src={album.coverPhoto.url} alt={album.name} /> : <div className="album-placeholder-small"><ImageIcon size={24} color="#888" /></div>}
                  </div>
                  <div className="album-select-info"><h3>{album.name}</h3><p>{album.photoCount} photos</p></div>
                  <div className={`check-circle ${safeTargetAlbumIds.includes(album.id) ? 'checked' : ''}`} style={{ marginLeft: 'auto', width: '22px', height: '22px' }}><Check size={14} /></div>
                </div>
            )) : <p>No albums available. Create one first!</p>}
          </div>
          <div className="modal-actions">
            <button className="btn" onClick={onClose}>Cancel</button>
            <button
              className="btn btn-primary"
              onClick={() => {
                handleAddPhotosToAlbums();
              }}
              disabled={safeTargetAlbumIds.length === 0}
            >
              Add to Albums
            </button>
          </div>
        </div>
      </div>
  );
}

export default AddToAlbumModal;