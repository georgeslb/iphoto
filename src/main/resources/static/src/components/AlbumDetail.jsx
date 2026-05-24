import React from 'react';
import { useParams } from 'react-router-dom';
import PhotoGrid from './PhotoGrid'; // Assuming PhotoGrid is in the same components directory

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
  const { albumId } = useParams();
  const currentAlbum = albums.find(a => a.id === albumId);

  if (!currentAlbum && albums.length > 0) return <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>Album not found.</div>;
  if (!currentAlbum) return <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>Loading album...</div>;

  return (
    <div className="content-body">
      <div className="album-detail-header">
        <h2>{currentAlbum.name}</h2>
        <p>{currentAlbum.description}</p>
        <span className="count">{currentAlbum.photoCount} photos</span>
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
      ) : <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>This album is empty. Add some photos!</div>}
    </div>
  );
}

export default AlbumDetail;