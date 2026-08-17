import React, { useCallback } from 'react';
import Lightbox from './Lightbox'; // Assuming Lightbox is in the same components directory

function LightboxWrapper({
  isLightboxOpen,
  selectedPhoto,
  onClose,
  extMetadata,
  backgroundLocation,
  handleMoveToGarbage,
  handleMoveToMain,
  handleRestore,
  handlePermanentDelete,
  photos, // Main photos array
  currentDuplicateGroup, // For duplicates context
  onNavigate, // handlePhotoClick
  onUpdateDate,
  onEditPhoto,
  onShowAddToAlbumModal,
  onShowRemoveFromAlbumModal, // New prop
  photoSelection,
}) {
  const getActivePhotosContext = useCallback(() => {
    const effectivePath = backgroundLocation?.pathname || window.location.pathname; // Use window.location.pathname as a fallback
    if (effectivePath.startsWith('/duplicates')) {
      return currentDuplicateGroup ? currentDuplicateGroup.photos : [];
    }
    return photos;
  }, [backgroundLocation, currentDuplicateGroup, photos]);

  if (!isLightboxOpen || !selectedPhoto) {
    return null;
  }

  return (
    <Lightbox
      selectedPhoto={selectedPhoto}
      onClose={onClose}
      extMetadata={extMetadata}
      pathname={backgroundLocation?.pathname || window.location.pathname} // Use window.location.pathname as a fallback
      handleMoveToGarbage={handleMoveToGarbage}
      handleMoveToMain={handleMoveToMain}
      handleRestore={handleRestore}
      handlePermanentDelete={handlePermanentDelete}
      photos={getActivePhotosContext()}
      onNavigate={onNavigate}
      onUpdateDate={onUpdateDate}
      onEditPhoto={onEditPhoto}
      onShowAddToAlbumModal={onShowAddToAlbumModal}
      onShowRemoveFromAlbumModal={onShowRemoveFromAlbumModal} // Pass the new prop
      photoSelection={photoSelection}
    />
  );
}

export default LightboxWrapper;