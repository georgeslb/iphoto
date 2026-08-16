import React from 'react';
import { Image as ImageIcon } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useLocale } from '../LocaleContext.jsx';
import EntityGridDisplay from './EntityGridDisplay';

function AlbumGridDisplay({
  albums,
  selectedIds,
  setSelectedIds,
}) {
  const { messages } = useLocale();
  const navigate = useNavigate();

  const toggle = (id) => setSelectedIds(v => v.includes(id) ? v.filter(i => i !== id) : [...v, id]);

  return (
    <EntityGridDisplay
      items={albums.map(album => ({ ...album, photoCountLabel: messages["photosCount"](album.photoCount) }))}
      selectedIds={selectedIds}
      onToggleSelect={toggle}
      onOpen={(album) => navigate(`/albums/${album.id}`)}
      renderCover={(album) => (
        album.coverPhoto ? (
          <img src={`/api/photos/${album.coverPhoto.id}/thumbnail`} alt={album.name} className="album-cover" />
        ) : (
          <div className="album-cover">
            <div className="album-placeholder"><ImageIcon size={48} color="#888" /></div>
          </div>
        )
      )}
      emptyMessage={messages["noAlbumsCreatedYet"]}
    />
  )
}

export default AlbumGridDisplay;
