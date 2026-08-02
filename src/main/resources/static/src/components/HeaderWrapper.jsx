import React from 'react';
import { useLocation } from 'react-router-dom';
import Header from './Header';

function HeaderWrapper({
  selectedIds,
  setSelectedIds,
  handleRestore,
  handlePermanentDelete,
  handleRemovePhotosFromAlbum,
  handleMoveToGarbage,
  setShowAddToAlbumModal,
  setShowCreateShareLinkModal,
  handleUpload,
  albumId,
  spaceId,
  handleSetCoverPhoto,
  handleDeleteAlbums,
  navigate,
  duplicatesPage,
  fetchDuplicates,
  isLastDuplicateGroup,
  photos,
  currentDuplicateGroup,
  backgroundLocation,
  fileInputRef,
  allowCopyToMain, // Existing prop
  refreshAndMaintainScroll, // Existing prop
  isAlbumAllowedInCurrentSpace, // NEW PROP from App.jsx
}) {
  const location = useLocation();
  const effectivePath = backgroundLocation?.pathname || location.pathname;
  const singleSelectedPhoto = (selectedIds.length === 1)
      ? photos.find(p => p.id === selectedIds[0]) || currentDuplicateGroup?.photos?.find(p => p.id === selectedIds[0])
      : null;
  const canSetCoverPhoto = singleSelectedPhoto && !singleSelectedPhoto.contentType.startsWith('video');

  return (
      <Header
          selectedIds={selectedIds}
          setSelectedIds={setSelectedIds}
          pathname={effectivePath}
          handleRestore={handleRestore}
          handlePermanentDelete={handlePermanentDelete}
          handleRemovePhotosFromAlbum={() => handleRemovePhotosFromAlbum(albumId)}
          handleMoveToGarbage={handleMoveToGarbage}
          setShowAddToAlbumModal={setShowAddToAlbumModal}
          setShowCreateShareLinkModal={(albumIdFromHeader) => {
            setShowCreateShareLinkModal(albumIdFromHeader);
          }}
          handleUpload={handleUpload}
          albumId={albumId}
          spaceId={spaceId}
          canSetCoverPhoto={canSetCoverPhoto}
          handleSetCoverPhoto={handleSetCoverPhoto}
          handleDeleteAlbums={handleDeleteAlbums}
          navigate={navigate}
          duplicatesPage={duplicatesPage}
          fetchDuplicates={fetchDuplicates}
          isLastDuplicateGroup={isLastDuplicateGroup}
          fileInputRef={fileInputRef}
          allowCopyToMain={allowCopyToMain} // Pass existing prop
          refreshAndMaintainScroll={refreshAndMaintainScroll} // Pass existing prop
          isAlbumAllowedInCurrentSpace={isAlbumAllowedInCurrentSpace} // Pass new prop
      />
  );
}

export default HeaderWrapper;