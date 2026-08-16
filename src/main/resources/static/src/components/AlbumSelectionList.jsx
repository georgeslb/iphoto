import React from 'react';
import { Image as ImageIcon, Check, X } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx';

function AlbumSelectionList({ albums, selectedIds, lockedIds = [], onToggle, variant = 'add', emptyMessage }) {
  const { messages } = useLocale();
  const isRemove = variant === 'remove';

  if (albums.length === 0) {
    return <p>{emptyMessage}</p>;
  }

  return (
    <div className="album-list-selection">
      {albums.map(album => {
        const isSelected = selectedIds.includes(album.id);
        const isLocked = lockedIds.includes(album.id);
        return (
          <div
            key={album.id}
            className={`album-select-item ${isSelected ? (isRemove ? 'remove-selected' : 'selected') : ''} ${isLocked ? 'locked' : ''}`}
            onClick={() => !isLocked && onToggle(album.id)}
          >
            <div className="album-cover-small">
              {album.coverPhoto ? <img src={album.coverPhoto.url} alt={album.name} /> : <div className="album-placeholder-small"><ImageIcon size={24} color="#888" /></div>}
            </div>
            <div className="album-select-info">
              <h3>{album.name}</h3>
              <p>{messages["photosCount"](album.photoCount)}</p>
            </div>
            {isSelected && (
              <div
                className="check-circle checked"
                style={isRemove
                  ? { marginLeft: 'auto', width: '22px', height: '22px', backgroundColor: '#ff3b30', borderColor: '#ff3b30' }
                  : { marginLeft: 'auto', width: '22px', height: '22px' }}
              >
                {isRemove ? <X size={14} /> : <Check size={14} />}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

export default AlbumSelectionList;
