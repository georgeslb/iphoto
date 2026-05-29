import React from 'react';
import { Image as ImageIcon, Check, Plus, Circle } from 'lucide-react'; /* Keep Circle for unchecked state */
import { useNavigate } from 'react-router-dom';
import CreateAlbumModal from './CreateAlbumModal'; // Import CreateAlbumModal


function AlbumGridDisplay({
  albums,
  selectedIds,
  setSelectedIds,
  newAlbumName,
  setNewAlbumName,
  newAlbumDescription,
  setNewAlbumDescription,
  albumCreationError,
  handleCreateAlbum,
  showCreateAlbumModal,
  setShowCreateAlbumModal,
}) {
  const navigate = useNavigate();

  return (
    <div className="content-body">
      <div className="album-grid">
        {albums.map(album => {
          const isSelected = selectedIds.includes(album.id);
          return (
            <div
              key={album.id}
              className={`album-card ${isSelected ? 'selected' : ''}`}
              onClick={() => {
                if (selectedIds.length > 0) {
                  setSelectedIds(v => v.includes(album.id) ? v.filter(i => i !== album.id) : [...v, album.id]);
                } else {
                  navigate(`/albums/${album.id}`);
                }
              }}
            >
              <div
                className="select-overlay"
                style={{ opacity: 1, pointerEvents: selectedIds.length > 0 ? 'all' : 'none' }} /* Ensure overlay is always visible but only interactive in selection mode */
                onClick={(e) => {
                  e.stopPropagation();
                  setSelectedIds(v => v.includes(album.id) ? v.filter(i => i !== album.id) : [...v, album.id]);
                }}
              >
                <div className={`check-circle ${isSelected ? 'checked' : ''}`}>
                  {isSelected ? <Check size={14} /> : <Circle size={14} color="#ccc" />} {/* Corrected: show Circle when not selected */}
                </div>
              </div>

              <div className="album-cover">
                {album.coverPhoto ? <img src={album.coverPhoto.url} alt={album.name} /> : <div className="album-placeholder"><ImageIcon size={48} color="#888" /></div>}
              </div>
              <div className="album-info">
                <h3>{album.name}</h3>
                <p>{album.photoCount} photos</p>
              </div>
            </div>
          );
        })}
        {albums.length === 0 && ( <div style={{textAlign: 'center', padding: '50px', color: '#888', gridColumn: '1/-1'}}> No albums created yet. Click "Create Album" to get started! </div>)}
          <div>
              <button className="btn btn-primary" onClick={() => setShowCreateAlbumModal(true)}
                      style={{marginTop: '20px'}}>
                  <Plus size={18}/> Create Album
              </button>
          </div>

      <CreateAlbumModal
        show={showCreateAlbumModal}
        onClose={() => setShowCreateAlbumModal(false)}
        newAlbumName={newAlbumName}
        setNewAlbumName={setNewAlbumName}
        newAlbumDescription={newAlbumDescription}
        setNewAlbumDescription={setNewAlbumDescription}
        albumCreationError={albumCreationError}
        handleCreateAlbum={handleCreateAlbum}
      />
      </div>
    </div>
  )
}

export default AlbumGridDisplay;