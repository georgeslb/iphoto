import React, { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Edit } from 'lucide-react';
import PhotoGrid from './PhotoGrid'; // Assuming PhotoGrid is in the same components directory
import PersonStrip from './PersonStrip';
import PersonMergePrompt from './PersonMergePrompt';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale
import { useEntityById } from '../hooks/useEntityById';
import { usePersonRename } from '../hooks/usePersonRename';

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
  const { entity: currentPerson, setEntity: setCurrentPerson, loading, error } = useEntityById(personId, '/api/faces/person');
  const [isEditingName, setIsEditingName] = useState(false);
  const [editedName, setEditedName] = useState('');
  // Bumped after a successful rename/merge so PersonStrip re-fetches — a merge can delete a
  // merged-away person from that list or change someone's cover photo, neither of which
  // PersonStrip's own internal usePersons() call would otherwise notice.
  const [personStripRefreshToken, setPersonStripRefreshToken] = useState(0);

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
