import React, { useMemo, useState } from 'react';
import { Image as ImageIcon, Search } from 'lucide-react';
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
  const [searchQuery, setSearchQuery] = useState('');

  const toggle = (id) => setSelectedIds(v => v.includes(id) ? v.filter(i => i !== id) : [...v, id]);

  // Case-insensitive substring filter over the already-loaded `albums` array (GET /api/albums
  // returns the full unpaginated list) - no backend change needed. No extra sort on top of the
  // filter: AlbumServiceImpl.getAllAlbums already returns albums sorted by name, so the filtered
  // result stays alphabetical for free. Plain useMemo, no debounce - filtering an already-loaded
  // array has no network round-trip per keystroke, and album counts are realistically small enough
  // (tens, not thousands) for a re-filter on every keystroke to stay smooth.
  const filteredAlbums = useMemo(() => {
    const query = searchQuery.trim().toLowerCase();
    if (!query) return albums;
    return albums.filter(album => album.name?.toLowerCase().includes(query));
  }, [albums, searchQuery]);

  return (
    <>
      {/* Rendered as a sibling directly ahead of EntityGridDisplay's own .content-body (not nested
          inside one), carrying its own matching side padding - same established pattern as
          LibraryTimeline.jsx's .library-timeline. */}
      <div className="album-search-bar">
        <Search size={18} className="album-search-icon" />
        <input
          type="text"
          className="modal-input album-search-input"
          placeholder={messages["searchAlbumsPlaceholder"]}
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
        />
      </div>
      <EntityGridDisplay
        items={filteredAlbums.map(album => ({ ...album, photoCountLabel: messages["photosCount"](album.photoCount) }))}
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
        emptyMessage={searchQuery.trim() ? messages["noAlbumsMatchSearch"] : messages["noAlbumsCreatedYet"]}
      />
    </>
  )
}

export default AlbumGridDisplay;
