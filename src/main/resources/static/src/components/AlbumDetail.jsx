import React from 'react';
import { useParams } from 'react-router-dom';
import PhotoGrid from './PhotoGrid'; // Assuming PhotoGrid is in the same components directory
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

function AlbumDetail({
  albums,
  photos,
  hasMore,
  setPage,
  selectedIds,
  setSelectedIds,
  handlePhotoClick,
  isLightboxOpen
}) {
  const { messages } = useLocale(); // Use the useLocale hook
  const { albumId } = useParams();
  const currentAlbum = albums.find(a => a.id === albumId);

  if (!currentAlbum && albums.length > 0) return <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["albumNotFound"]}</div>;
  if (!currentAlbum) return <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["loadingAlbum"]}</div>;

  return (
    <div className="content-body">
      <div className="album-detail-header">
        <h2>{currentAlbum.name}</h2>
        <p>{currentAlbum.description}</p>
        <span className="count">{messages["photosCount"](currentAlbum.photoCount)}</span>
      </div>
      {photos.length > 0 ? (
          <PhotoGrid
              photos={photos}
              hasMore={hasMore}
              setPage={setPage}
              selectedIds={selectedIds}
              setSelectedIds={setSelectedIds}
              setSelectedPhoto={handlePhotoClick}
              isLightboxOpen={isLightboxOpen}
          />
      ) : <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["albumEmpty"]}</div>}
    </div>
  );
}

export default AlbumDetail;