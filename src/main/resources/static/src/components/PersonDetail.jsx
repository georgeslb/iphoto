import React, { useCallback } from 'react';
import { useParams } from 'react-router-dom';
import PhotoGrid from './PhotoGrid'; // Assuming PhotoGrid is in the same components directory
import PersonStrip from './PersonStrip';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale
import { useEntityById } from '../hooks/useEntityById';

function PersonDetail({
  photos,
  hasMore,
  setPage,
  selectedIds,
  setSelectedIds,
  handlePhotoClick,
  isLightboxOpen,
  handleMoveToGarbage,
  refreshAndMaintainScroll
}) {
  const { messages } = useLocale(); // Use the useLocale hook
  const { personId } = useParams();
  const { entity: currentPerson, loading, error } = useEntityById(personId, '/api/faces/person');

  const handleUnlinkPerson = useCallback(async (photoId) => {
    try {
      const response = await fetch(`/api/faces/person/${personId}/photo/${photoId}`, { method: 'DELETE' });
      if (!response.ok) {
        throw new Error(messages["failedToUnlinkPerson"]);
      }
      await refreshAndMaintainScroll();
    } catch (error) {
      console.error(messages["failedToUnlinkPerson"], error);
    }
  }, [personId, refreshAndMaintainScroll, messages]);

  return (
    <div className="content-body">
      {/* Lets the user jump straight to another person without going back to the /persons grid */}
      <PersonStrip selectedPersonId={personId} />

      {loading ? (
        <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["loadingPerson"]}</div>
      ) : error ? (
        <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>Error: {error.message}</div>
      ) : !currentPerson ? (
        <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["personNotFound"]}</div>
      ) : (
        <>
          <div className="album-detail-header"> {/* Reusing album-detail-header styling */}
            <h2>{currentPerson.name || messages["whoIsThis"]}</h2>
            <span className="count">{messages["photosCount"](currentPerson.photoCount || 0)}</span>
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
                  handleUnlinkPerson={handleUnlinkPerson}
              />
          ) : <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["personEmpty"]}</div>}
        </>
      )}
    </div>
  );
}

export default PersonDetail;
