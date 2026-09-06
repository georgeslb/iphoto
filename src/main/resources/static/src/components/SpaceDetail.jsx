import React from 'react';
import { useParams } from 'react-router-dom';
import PhotoGrid from './PhotoGrid'; // Assuming PhotoGrid is in the same components directory
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale
import { useEntityById } from '../hooks/useEntityById';

function SpaceDetail({
  dayIndex,
  selectedIds,
  setSelectedIds,
  handlePhotoClick,
  isLightboxOpen,
  handleMoveToGarbage,
  handleReorderPhotos
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
      {dayIndex.loadingDays ? (
        <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["loadingMorePhotos"]}</div>
      ) : dayIndex.days.length > 0 ? (
          <PhotoGrid
              dayIndex={dayIndex}
              selectedIds={selectedIds}
              setSelectedIds={setSelectedIds}
              setSelectedPhoto={handlePhotoClick}
              isLightboxOpen={isLightboxOpen}
              handleMoveToGarbage={handleMoveToGarbage}
              handleReorderPhotos={handleReorderPhotos}
          />
      ) : <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["spaceEmpty"]}</div>}
    </div>
  );
}

export default SpaceDetail;
