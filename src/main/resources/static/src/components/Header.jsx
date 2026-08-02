import React, { useCallback } from 'react';
import { X, Upload, Plus, Trash2, RotateCcw, ImageIcon, ArrowLeft, ChevronLeft, ChevronRight, Link, Home } from 'lucide-react'; // Added Home icon
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

export default function Header({
  selectedIds,
  setSelectedIds,
  pathname,
  handleRestore,
  handlePermanentDelete,
  handleRemovePhotosFromAlbum,
  handleMoveToGarbage,
  setShowAddToAlbumModal,
  setShowCreateShareLinkModal,
  handleUpload,
  albumId,
  spaceId,
  canSetCoverPhoto,
  handleSetCoverPhoto,
  navigate,
  duplicatesPage,
  fetchDuplicates,
  isLastDuplicateGroup,
  handleDeleteAlbums,
  fileInputRef,
  allowCopyToMain, // Existing prop
  refreshAndMaintainScroll, // Existing prop
  isAlbumAllowedInCurrentSpace, // NEW PROP from HeaderWrapper
}) {
  const { messages } = useLocale(); // Use the useLocale hook
  const selectionMode = selectedIds.length > 0;
  const isDuplicatesPage = pathname.startsWith('/duplicates');
  const isAlbumsMainPage = pathname === '/albums';
  const isSpacesPage = pathname.startsWith('/spaces/') && spaceId; // Check if it's a space detail page and spaceId is present

  const handleUploadClick = useCallback(() => {
    if (fileInputRef.current) {
      fileInputRef.current.dataset.albumId = albumId || '';
      fileInputRef.current.dataset.spaceId = spaceId || '';
      fileInputRef.current.click();
    }
  }, [albumId, spaceId, fileInputRef]);

  const handleMoveToMain = useCallback(async () => {
    if (selectedIds.length === 0) return;

    if (!window.confirm(messages["moveToMainConfirmation"](selectedIds.length))) {
      return;
    }

    try {
      const response = await fetch('/api/photos/main', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(selectedIds),
      });

      if (response.ok) {
        console.log(messages["photosMovedToMainSuccess"]);
        setSelectedIds([]); // Clear selection
        refreshAndMaintainScroll(); // Refresh the current view
      } else {
        console.error('Failed to move photos to Main Space:', response.statusText);
        alert(messages["failedToMovePhotosToMain"]);
      }
    } catch (error) {
      console.error('Error moving photos to Main Space:', error);
      alert(messages["errorMovingPhotosToMain"]);
    }
  }, [selectedIds, refreshAndMaintainScroll, setSelectedIds, messages]);

  return (
    <header className="header">
      <div>
        {pathname.startsWith('/albums/') && albumId && (
          <button className="btn" onClick={() => navigate('/albums')}><ArrowLeft size={18} /> {messages["backToAlbums"]}</button>
        )}
        {isSpacesPage && (
          <button className="btn" onClick={() => navigate('/spaces')}><ArrowLeft size={18} /> {messages["backToSpaces"]}</button>
        )}
      </div>

      <div style={{ display: 'flex', gap: '12px', alignItems: 'center' }}>
        {selectionMode ? (
          <>
            <button className="btn" onClick={() => setSelectedIds([])}><X size={18}/> {messages["cancel"]}</button>
            <span style={{fontWeight: 600}}>{messages["selectedCount"](selectedIds.length)}</span>
            {pathname === '/garbage' ? (
              <>
                <button className="btn" style={{color: '#34c759'}} onClick={() => handleRestore(selectedIds)} disabled={selectedIds.length === 0}><RotateCcw size={18}/> {messages["restore"]}</button>
                <button className="btn" style={{color: '#ff3b30'}} onClick={() => handlePermanentDelete(selectedIds)} disabled={selectedIds.length === 0}><Trash2 size={18}/> {messages["deletePermanently"]}</button>
              </>
            ) : pathname.startsWith('/albums/') && albumId ? (
              <>
                {canSetCoverPhoto && (
                  <button className="btn btn-primary" onClick={() => handleSetCoverPhoto(albumId, selectedIds[0])}>
                    <ImageIcon size={18}/> {messages["setAsCover"]}
                  </button>
                )}
                <button className="btn" style={{color: '#ff3b30'}} onClick={() => handleRemovePhotosFromAlbum(albumId)} disabled={selectedIds.length === 0}><Trash2 size={18}/> {messages["removeFromAlbum"]}</button>
              </>
            ) : isAlbumsMainPage ? (
              <>
                <button className="btn" style={{color: '#ff3b30'}} onClick={() => handleDeleteAlbums(selectedIds)} disabled={selectedIds.length === 0}><Trash2 size={18}/> {messages["deleteAlbums"]}</button>
              </>
            ) : ( // Default actions for main library or spaces
              <>
                {isSpacesPage && allowCopyToMain && (
                  <button className="btn" style={{backgroundColor: '#28a745', color: 'white'}} onClick={handleMoveToMain} disabled={selectedIds.length === 0}><Home size={18}/> {messages["moveToMain"]}</button>
                )}
                {/* Conditional rendering for Add to Album button */}
                {(pathname === '/' || (isSpacesPage && isAlbumAllowedInCurrentSpace)) && (
                  <button className="btn btn-primary" onClick={() => setShowAddToAlbumModal(true)} disabled={selectedIds.length === 0}><Plus size={18}/> {messages["addToAlbum"]}</button>
                )}
                <button className="btn" style={{color: '#ff3b30'}} onClick={() => handleMoveToGarbage(selectedIds)} disabled={selectedIds.length === 0}><Trash2 size={18}/> {messages["moveToGarbage"]}</button>
              </>
            )}
          </>
        ) : (
          <>
            {isDuplicatesPage && (
              <>
                <button className="btn" onClick={() => fetchDuplicates(duplicatesPage - 1)} disabled={duplicatesPage === 0}><ChevronLeft size={18}/> {messages["prevGroup"]}</button>
                <button className="btn btn-primary" onClick={() => fetchDuplicates(duplicatesPage + 1)} disabled={isLastDuplicateGroup}> {messages["nextGroup"]} <ChevronRight size={18}/></button>
              </>
            )}

            {/* Upload button logic */}
            {(pathname === '/' || pathname.startsWith('/albums/') || isSpacesPage) && (
              <button className="btn btn-primary" onClick={handleUploadClick}><Upload size={18} /> {messages["upload"]}</button>
            )}

            {/* Create Share Link button for albums */}
            {pathname.startsWith('/albums/') && albumId && (
              <button className="btn" onClick={() => setShowCreateShareLinkModal(albumId)}><Link size={18} /> {messages["createShareLink"]}</button>
            )}
          </>
        )}
      </div>
      <input id="fIn" type="file" multiple onChange={handleUpload} style={{ display: 'none' }} ref={fileInputRef} />
    </header>
  );
}