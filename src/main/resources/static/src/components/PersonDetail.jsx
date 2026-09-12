import React, { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Edit } from 'lucide-react';
import PhotoGrid from './PhotoGrid'; // Assuming PhotoGrid is in the same components directory
import PersonFacesGrid from './PersonFacesGrid';
import PersonStrip from './PersonStrip';
import PersonMergePrompt from './PersonMergePrompt';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale
import { useEntityById } from '../hooks/useEntityById';
import { usePersonRename } from '../hooks/usePersonRename';
import { usePersonFaces } from '../hooks/usePersonFaces';

function PersonDetail({
  dayIndex,
  selectedIds,
  setSelectedIds,
  handlePhotoClick,
  isLightboxOpen,
  handleMoveToGarbage,
  refreshAndMaintainScroll,
  handleReorderPhotos
}) {
  const { messages } = useLocale(); // Use the useLocale hook
  const { personId } = useParams();
  const { entity: currentPerson, setEntity: setCurrentPerson, loading, error } = useEntityById(personId, '/api/faces/person');
  const [isEditingName, setIsEditingName] = useState(false);
  const [editedName, setEditedName] = useState('');
  // Bumped after a successful rename/merge so PersonStrip re-fetches — a merge can delete a
  // merged-away person from that list or change someone's cover photo, neither of which
  // PersonStrip's own internal usePersons() call would otherwise notice.
  const [personStripRefreshToken, setPersonStripRefreshToken] = useState(0);
  // 'photos' - the existing day-indexed PhotoGrid. 'faces' - this person's own distinct face
  // crops, paginated newest-first, each unlinkable in place (see usePersonFaces).
  const [activeTab, setActiveTab] = useState('photos');
  const { faces, loading: facesLoading, hasMore: facesHasMore, loadMore: loadMoreFaces, unlinkFace } = usePersonFaces(personId);

  const {
    rename,
    collisionCandidates,
    pendingName,
    selectedMergeIds,
    toggleMergeCandidate,
    confirmRename,
    cancelRename,
    submitting,
  } = usePersonRename(useCallback(async (updatedPerson) => {
    setCurrentPerson(updatedPerson);
    setIsEditingName(false);
    setPersonStripRefreshToken(t => t + 1);
    // A confirmed merge can pull in photos this person didn't have a moment ago (every photo the
    // merged-away person(s) appeared in) — refresh the grid below, same as every other
    // face/person mutation flow on this page already does (see handleUnlinkPerson).
    if (refreshAndMaintainScroll) await refreshAndMaintainScroll();
  }, [setCurrentPerson, refreshAndMaintainScroll]));

  // Keep the edit field in sync if the person changes out from under us (navigating to a
  // different /persons/:id while mid-edit, or the entity finishing its initial fetch).
  useEffect(() => {
    if (!isEditingName && currentPerson) {
      setEditedName(currentPerson.name || '');
    }
  }, [currentPerson, isEditingName]);

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

  // Unlinking a face can drop a photo out of this person's set entirely (if it was the only face
  // of theirs on it) and always changes photoCount, so both the Photos tab's day index and the
  // header's own count need to follow it - same pattern as handleUnlinkPerson above and
  // Lightbox.jsx's own handleUnlinkFacePerson, just triggered from the Faces tab instead.
  const handleUnlinkFace = useCallback(async (faceId) => {
    try {
      await unlinkFace(faceId);
      const response = await fetch(`/api/faces/person/${personId}`);
      if (response.ok) {
        setCurrentPerson(await response.json());
      }
      if (refreshAndMaintainScroll) await refreshAndMaintainScroll();
    } catch (error) {
      console.error(messages["errorUnlinkingFace"], error);
    }
  }, [unlinkFace, personId, setCurrentPerson, refreshAndMaintainScroll, messages]);

  const startEditingName = () => {
    setEditedName(currentPerson.name || '');
    setIsEditingName(true);
  };

  const submitNameEdit = () => {
    const trimmed = editedName.trim();
    if (!trimmed || trimmed === currentPerson.name) {
      setIsEditingName(false);
      setEditedName(currentPerson.name || '');
      return;
    }
    rename(personId, trimmed);
  };

  return (
    <div className="content-body">
      {/* Lets the user jump straight to another person without going back to the /persons grid */}
      <PersonStrip selectedPersonId={personId} refreshToken={personStripRefreshToken} />

      {loading ? (
        <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["loadingPerson"]}</div>
      ) : error ? (
        <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>Error: {error.message}</div>
      ) : !currentPerson ? (
        <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["personNotFound"]}</div>
      ) : (
        <>
          <div className="album-detail-header"> {/* Reusing album-detail-header styling */}
            {isEditingName ? (
              <input
                type="text"
                className="person-title-name-input"
                value={editedName}
                onChange={(e) => setEditedName(e.target.value)}
                onBlur={submitNameEdit}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') submitNameEdit();
                  else if (e.key === 'Escape') { setIsEditingName(false); setEditedName(currentPerson.name || ''); }
                }}
                placeholder={messages["personNamePlaceholder"]}
                autoFocus
              />
            ) : (
              <h2 onClick={startEditingName} className="person-title-name" title={messages["editFaceName"]}>
                {currentPerson.name || messages["whoIsThis"]}
                <Edit size={16} className="edit-icon"/>
              </h2>
            )}
            <span className="count">{messages["photosCount"](currentPerson.photoCount || 0)}</span>
          </div>

          <div className="person-tabs">
            <button
              type="button"
              className={`person-tab-button${activeTab === 'photos' ? ' active' : ''}`}
              onClick={() => setActiveTab('photos')}
            >
              {messages["personTabPhotos"]}
            </button>
            <button
              type="button"
              className={`person-tab-button${activeTab === 'faces' ? ' active' : ''}`}
              onClick={() => setActiveTab('faces')}
            >
              {messages["personTabFaces"]}
            </button>
          </div>

          {activeTab === 'photos' ? (
            dayIndex.loadingDays ? (
                <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["loadingMorePhotos"]}</div>
            ) : dayIndex.days.length > 0 ? (
                <PhotoGrid
                    dayIndex={dayIndex}
                    selectedIds={selectedIds}
                    setSelectedIds={setSelectedIds}
                    setSelectedPhoto={handlePhotoClick}
                    isLightboxOpen={isLightboxOpen}
                    handleMoveToGarbage={handleMoveToGarbage}
                    handleUnlinkPerson={handleUnlinkPerson}
                    handleReorderPhotos={handleReorderPhotos}
                />
            ) : <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>{messages["personEmpty"]}</div>
          ) : (
            <PersonFacesGrid
                faces={faces}
                loading={facesLoading}
                hasMore={facesHasMore}
                onLoadMore={loadMoreFaces}
                onUnlink={handleUnlinkFace}
            />
          )}
        </>
      )}

      {collisionCandidates && (
        <PersonMergePrompt
          name={pendingName}
          candidates={collisionCandidates}
          selectedIds={selectedMergeIds}
          onToggle={toggleMergeCandidate}
          onCancel={cancelRename}
          onConfirm={confirmRename}
          submitting={submitting}
        />
      )}
    </div>
  );
}

export default PersonDetail;
