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

  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <h2>Add to Album</h2>
        <p>Select albums to add {selectedIds.length} photo(s) to:</p>
        <div className="album-list-selection">
          {albums.length > 0 ? albums.map(album => (
              <div
                  key={album.id}
                  className={`album-select-item ${targetAlbumIds.includes(album.id) ? 'selected' : ''}`}
                  onClick={() => {
                    setTargetAlbumIds(prev =>
                        prev.includes(album.id)
                            ? prev.filter(id => id !== album.id)
                            : [...prev, album.id]
                    );
                  }}
              >
                <div className="album-cover-small">
                  {album.coverPhoto ? <img src={album.coverPhoto.url} alt={album.name} /> : <div className="album-placeholder-small"><ImageIcon size={24} color="#888" /></div>}
                </div>
                <div className="album-select-info"><h3>{album.name}</h3><p>{album.photoCount} photos</p></div>
                {targetAlbumIds.includes(album.id) && <div className="check-circle checked" style={{ marginLeft: 'auto', width: '22px', height: '22px' }}><Check size={14} /></div>}
              </div>
          )) : <p>No albums available. Create one first!</p>}
        </div>
        <div className="modal-actions">
          <button className="btn" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" onClick={handleAddPhotosToAlbums} disabled={targetAlbumIds.length === 0}>Add to Albums</button>
        </div>
      </div>
    </div>
  );
}

export default AddToAlbumModal;