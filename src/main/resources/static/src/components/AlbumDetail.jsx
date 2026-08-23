import React from 'react';
import { useParams } from 'react-router-dom';
import PhotoGrid from './PhotoGrid'; // Assuming PhotoGrid is in the same components directory
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale
import { useEntityById } from '../hooks/useEntityById';

function AlbumDetail({
  photos,
  hasMore,
  setPage,
  selectedIds,
  setSelectedIds,
  handlePhotoClick,
  isLightboxOpen,
  handleMoveToGarbage
}) {
  const { messages } = useLocale(); // Use the useLocale hook
  const { albumId } = useParams();
  const { entity: currentAlbum, loading, error } = useEntityById(albumId, '/api/albums');

  if (loading) return <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["loadingAlbum"]}</div>;
  if (error) return <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>Error: {error.message}</div>;
  if (!currentAlbum) return <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["albumNotFound"]}</div>;

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
              handleMoveToGarbage={handleMoveToGarbage}
          />
      ) : <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["albumEmpty"]}</div>}
    </div>
  );
}

export default AlbumDetail;
