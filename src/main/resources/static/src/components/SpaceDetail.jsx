import React from 'react';
import { useParams } from 'react-router-dom';
import PhotoGrid from './PhotoGrid'; // Assuming PhotoGrid is in the same components directory

function SpaceDetail({
  spaces, // Now receiving spaces instead of albums
  photos,
  hasMore,
  setPage,
  selectedIds,
  setSelectedIds,
  handlePhotoClick,
  isLightboxOpen
}) {
  const { spaceId } = useParams();
  const currentSpace = spaces.find(s => s.id === spaceId);

  if (!currentSpace && spaces.length > 0) return <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>Space not found.</div>;
  if (!currentSpace) return <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>Loading space...</div>;

  return (
    <div className="content-body">
      <div className="album-detail-header"> {/* Reusing album-detail-header styling */}
        <h2>{currentSpace.name}</h2>
        <p>{currentSpace.description}</p>
        <span className="count">{currentSpace.photoCount} photos</span>
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
      ) : <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>This space is empty. Add some photos!</div>}
    </div>
  );
}

export default SpaceDetail;