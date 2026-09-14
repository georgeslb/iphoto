import React, { useCallback, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Lock, Unlock } from 'lucide-react';
import PhotoGrid from './PhotoGrid'; // Assuming PhotoGrid is in the same components directory
import RecentlyAddedSection from './RecentlyAddedSection';
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
  const { entity: currentSpace, setEntity: setCurrentSpace, loading, error } = useEntityById(spaceId, '/api/spaces');
  const [unlocking, setUnlocking] = useState(false);

  // Block/unblock-on-sync (see specification.md §4.2): this Space is locked to whichever device
  // is actively mirroring an on-device folder into it - a web session is always allowed to break
  // that lock (the "I lost my phone" escape hatch, DELETE /api/spaces/{id}/sync-lock), but only
  // after an explicit confirmation naming the device, since it stops that device's sync outright
  // (the app surfaces this to the user the next time it connects - see TODO.md).
  const handleUnlockSpace = useCallback(async () => {
    if (!currentSpace) return;
    const confirmed = window.confirm(messages["unlockSpaceConfirm"](currentSpace.syncLockedByDeviceName));
    if (!confirmed) return;
    setUnlocking(true);
    try {
      const response = await fetch(`/api/spaces/${spaceId}/sync-lock`, { method: 'DELETE' });
      if (!response.ok) {
        throw new Error(messages["failedToUnlockSpace"]);
      }
      setCurrentSpace(await response.json());
    } catch (err) {
      console.error(messages["failedToUnlockSpace"], err);
      alert(messages["failedToUnlockSpace"]);
    } finally {
      setUnlocking(false);
    }
  }, [currentSpace, spaceId, setCurrentSpace, messages]);

  if (loading) return <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["loadingSpace"]}</div>;
  if (error) return <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>Error: {error.message}</div>;
  if (!currentSpace) return <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["spaceNotFound"]}</div>;

  return (
    <div className="content-body">
      <div className="album-detail-header"> {/* Reusing album-detail-header styling */}
        <h2>{currentSpace.name}</h2>
        {currentSpace.syncLocked && (
          <span className="space-sync-lock-badge" title={messages["spaceSyncLockedTitle"](currentSpace.syncLockedByDeviceName)}>
            <Lock size={16} />
            {messages["spaceSyncLockedLabel"]}
            <button
              type="button"
              className="btn space-unlock-btn"
              onClick={handleUnlockSpace}
              disabled={unlocking}
              title={messages["unlockSpaceButtonTitle"]}
            >
              <Unlock size={14} /> {messages["unlockSpaceButton"]}
            </button>
          </span>
        )}
        <p>{currentSpace.description}</p>
        <span className="count">{messages["photosCount"](currentSpace.photoCount)}</span>
      </div>
      <RecentlyAddedSection
          baseUrl={`/api/photos/space/${spaceId}/recent`}
          onPhotoClick={handlePhotoClick}
          isLightboxOpen={isLightboxOpen}
      />
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
