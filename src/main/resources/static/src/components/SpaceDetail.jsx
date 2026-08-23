import React from 'react';
import { useParams } from 'react-router-dom';
import PhotoGrid from './PhotoGrid'; // Assuming PhotoGrid is in the same components directory
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale
import { useEntityById } from '../hooks/useEntityById';

function SpaceDetail({
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
  const { spaceId } = useParams();
  const { entity: currentSpace, loading, error } = useEntityById(spaceId, '/api/spaces');

  if (loading) return <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["loadingSpace"]}</div>;
  if (error) return <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>Error: {error.message}</div>;
  if (!currentSpace) return <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["spaceNotFound"]}</div>;

  return (
    <div className="content-body">
      <div className="album-detail-header"> {/* Reusing album-detail-header styling */}
        <h2>{currentSpace.name}</h2>
        <p>{currentSpace.description}</p>
        <span className="count">{messages["photosCount"](currentSpace.photoCount)}</span>
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
      ) : <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["spaceEmpty"]}</div>}
    </div>
  );
}

export default SpaceDetail;
