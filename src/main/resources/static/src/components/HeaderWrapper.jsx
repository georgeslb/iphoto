import React from 'react';
import { useLocation } from 'react-router-dom';
import Header from './Header';

function HeaderWrapper({
  selectedIds,
  setSelectedIds,
  selectedSpaceIds,
  selectedAlbumIds,
  setSelectedSpaceIds,
  setSelectedAlbumIds,
  menuSpaceId,
  menuAlbumId,
  handleRestore,
  handlePermanentDelete,
  handleRestoreSpaces,
  handlePermanentDeleteSpaces,
  handleRemovePhotosFromAlbum,
  handleMoveToGarbage,
  setShowAddToAlbumModal,
  setShowCreateShareLinkModal,
  handleUpload,
  handleSetCoverPhoto,
  handleDeleteAlbums,
  handleDeleteSpaces,
  setShowCreateAlbumModal,
  setShowCreateSpaceModal,
  navigate,
  duplicatesPage,
  fetchDuplicates,
  isLastDuplicateGroup,
  backgroundLocation,
  fileInputRef,
  photoSelection,
  refreshAndMaintainScroll, // Existing prop
  fromGarbage,
  setShowEditDateModal,
  handleDownload,
}) {
  const location = useLocation();
  const effectivePath = backgroundLocation?.pathname || location.pathname;

  return (
      <Header
          selectedIds={selectedIds}
          setSelectedIds={setSelectedIds}
          selectedSpaceIds={selectedSpaceIds}
          selectedAlbumIds={selectedAlbumIds}
          setSelectedSpaceIds={setSelectedSpaceIds}
          setSelectedAlbumIds={setSelectedAlbumIds}
          menuSpaceId={menuSpaceId}
          menuAlbumId={menuAlbumId}
          pathname={effectivePath}
          handleRestore={handleRestore}
          handlePermanentDelete={handlePermanentDelete}
          handleRestoreSpaces={handleRestoreSpaces}
          handlePermanentDeleteSpaces={handlePermanentDeleteSpaces}
          handleRemovePhotosFromAlbum={handleRemovePhotosFromAlbum}
          handleMoveToGarbage={handleMoveToGarbage}
          setShowAddToAlbumModal={setShowAddToAlbumModal}
          setShowCreateShareLinkModal={setShowCreateShareLinkModal}
          handleUpload={handleUpload}

          handleSetCoverPhoto={handleSetCoverPhoto}
          handleDeleteAlbums={handleDeleteAlbums}
          handleDeleteSpaces={handleDeleteSpaces}
          setShowCreateAlbumModal={setShowCreateAlbumModal}
          setShowCreateSpaceModal={setShowCreateSpaceModal}
          navigate={navigate}
          duplicatesPage={duplicatesPage}
          fetchDuplicates={fetchDuplicates}
          isLastDuplicateGroup={isLastDuplicateGroup}
          fileInputRef={fileInputRef}
          photoSelection={photoSelection}
          refreshAndMaintainScroll={refreshAndMaintainScroll} // Pass existing prop
          fromGarbage={fromGarbage}
          setShowEditDateModal={setShowEditDateModal}
          handleDownload={handleDownload}
      />
  );
}

export default HeaderWrapper;