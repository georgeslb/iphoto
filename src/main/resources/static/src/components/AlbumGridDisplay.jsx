import React from 'react';
import { Image as ImageIcon, Check } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

function AlbumGridDisplay({ albums, selectedIds, setSelectedIds }) {
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
                onClick={(e) => {
                  e.stopPropagation();
                  setSelectedIds(v => v.includes(album.id) ? v.filter(i => i !== album.id) : [...v, album.id]);
                }}
              >
                <div className={`check-circle ${isSelected ? 'checked' : ''}`}>
                  {isSelected && <Check size={14} />}
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
        {albums.length === 0 && <div style={{textAlign: 'center', padding: '50px', color: '#888', gridColumn: '1/-1'}}>No albums created yet. Click "Create Album" to get started!</div>}
      </div>
    </div>
  );
}

export default AlbumGridDisplay;