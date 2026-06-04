import React from 'react';
import { useLocation } from 'react-router-dom'; // Keep useLocation for effectivePath calculation
import Header from './Header';

function HeaderWrapper({
  selectedIds,
  setSelectedIds,
  handleRestore,
  handlePermanentDelete,
  handleRemovePhotosFromAlbum,
  handleMoveToGarbage,
  setShowAddToAlbumModal,
  setShowCreateShareLinkModal, // This is now the handler from App.jsx
  handleUpload,
  albumId, // Use the albumId passed as a prop
  spaceId, // Add spaceId prop here
  handleSetCoverPhoto,
  handleDeleteAlbums,
  navigate, // navigate is passed as a prop
  duplicatesPage,
  fetchDuplicates,
  isLastDuplicateGroup,
  photos,
  currentDuplicateGroup,
  backgroundLocation,
  fileInputRef, // Add fileInputRef to props
  allowCopyToMain, // New prop
  refreshAndMaintainScroll // New prop
}) {
  const location = useLocation();

  const effectivePath = backgroundLocation?.pathname || location.pathname;
  // Removed redundant routeAlbumId calculation, use albumId prop directly

  // console.log('HeaderWrapper: spaceId received as prop:', spaceId); // Removed for cleanup


  const singleSelectedPhoto = (selectedIds.length === 1)
      ? photos.find(p => p.id === selectedIds[0]) || currentDuplicateGroup?.photos?.find(p => p.id === selectedIds[0])
      : null;
  const canSetCoverPhoto = singleSelectedPhoto && !singleSelectedPhoto.contentType.startsWith('video'); // Calculate here

  return (
      <Header
          selectedIds={selectedIds}
          setSelectedIds={setSelectedIds}
          pathname={effectivePath}
          handleRestore={handleRestore}
          handlePermanentDelete={handlePermanentDelete}
          handleRemovePhotosFromAlbum={() => handleRemovePhotosFromAlbum(albumId)} // Use albumId prop
          handleMoveToGarbage={handleMoveToGarbage}
          setShowAddToAlbumModal={setShowAddToAlbumModal}
          setShowCreateShareLinkModal={(albumIdFromHeader) => { // Call the prop handler
            setShowCreateShareLinkModal(albumIdFromHeader);
          }}
          handleUpload={handleUpload}
          albumId={albumId} // Use the prop
          spaceId={spaceId} // Pass spaceId prop to Header
          canSetCoverPhoto={canSetCoverPhoto} // Use the calculated value
          handleSetCoverPhoto={handleSetCoverPhoto}
          handleDeleteAlbums={handleDeleteAlbums}
          navigate={navigate} // Use the prop
          duplicatesPage={duplicatesPage}
          fetchDuplicates={fetchDuplicates}
          isLastDuplicateGroup={isLastDuplicateGroup}
          fileInputRef={fileInputRef} // Pass fileInputRef to Header
          allowCopyToMain={allowCopyToMain} // Pass allowCopyToMain
          refreshAndMaintainScroll={refreshAndMaintainScroll} // Pass refresh function
      />
  );
}

export default HeaderWrapper;