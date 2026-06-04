import React, { useCallback } from 'react';
import { X, Upload, Plus, Trash2, RotateCcw, ImageIcon, ArrowLeft, ChevronLeft, ChevronRight, Link, Home } from 'lucide-react'; // Added Home icon

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
  allowCopyToMain, // New prop
  refreshAndMaintainScroll // New prop
}) {
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

    if (!window.confirm(`Are you sure you want to move ${selectedIds.length} selected photo(s) to the Main Space?`)) {
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
        console.log('Photos moved to Main Space successfully.');
        setSelectedIds([]); // Clear selection
        refreshAndMaintainScroll(); // Refresh the current view
      } else {
        console.error('Failed to move photos to Main Space:', response.statusText);
        alert('Failed to move photos to Main Space.');
      }
    } catch (error) {
      console.error('Error moving photos to Main Space:', error);
      alert('An error occurred while moving photos to Main Space.');
    }
  }, [selectedIds, refreshAndMaintainScroll, setSelectedIds]);

  return (
    <header className="header">
      <div>
        {pathname.startsWith('/albums/') && albumId && (
          <button className="btn" onClick={() => navigate('/albums')}><ArrowLeft size={18} /> Back to Albums</button>
        )}
        {isSpacesPage && (
          <button className="btn" onClick={() => navigate('/spaces')}><ArrowLeft size={18} /> Back to Spaces</button>
        )}
      </div>

      <div style={{ display: 'flex', gap: '12px', alignItems: 'center' }}>
        {selectionMode ? (
          <>
            <button className="btn" onClick={() => setSelectedIds([])}><X size={18}/> Cancel</button>
            <span style={{fontWeight: 600}}>{selectedIds.length} selected</span>
            {pathname === '/garbage' ? (
              <>
                <button className="btn" style={{color: '#34c759'}} onClick={() => handleRestore(selectedIds)} disabled={selectedIds.length === 0}><RotateCcw size={18}/> Restore</button>
                <button className="btn" style={{color: '#ff3b30'}} onClick={() => handlePermanentDelete(selectedIds)} disabled={selectedIds.length === 0}><Trash2 size={18}/> Delete Permanently</button>
              </>
            ) : pathname.startsWith('/albums/') && albumId ? (
              <>
                {canSetCoverPhoto && (
                  <button className="btn btn-primary" onClick={() => handleSetCoverPhoto(albumId, selectedIds[0])}>
                    <ImageIcon size={18}/> Set as Cover
                  </button>
                )}
                <button className="btn" style={{color: '#ff3b30'}} onClick={() => handleRemovePhotosFromAlbum(albumId)} disabled={selectedIds.length === 0}><Trash2 size={18}/> Remove from Album</button>
              </>
            ) : isAlbumsMainPage ? (
              <>
                <button className="btn" style={{color: '#ff3b30'}} onClick={() => handleDeleteAlbums(selectedIds)} disabled={selectedIds.length === 0}><Trash2 size={18}/> Delete Album(s)</button>
              </>
            ) : ( // Default actions for main library or spaces
              <>
                {isSpacesPage && allowCopyToMain && (
                  <button className="btn" style={{backgroundColor: '#28a745', color: 'white'}} onClick={handleMoveToMain} disabled={selectedIds.length === 0}><Home size={18}/> Move to Main</button>
                )}
                <button className="btn" style={{color: '#ff3b30'}} onClick={() => handleMoveToGarbage(selectedIds)} disabled={selectedIds.length === 0}><Trash2 size={18}/> Move to Garbage</button>
                {(pathname === '/' || isSpacesPage) && ( // Allow Add to Album from main library or spaces
                  <button className="btn btn-primary" onClick={() => setShowAddToAlbumModal(true)} disabled={selectedIds.length === 0}><Plus size={18}/> Add to Album</button>
                )}
              </>
            )}
          </>
        ) : (
          <>
            {isDuplicatesPage && (
              <>
                <button className="btn" onClick={() => fetchDuplicates(duplicatesPage - 1)} disabled={duplicatesPage === 0}><ChevronLeft size={18}/> Prev Group</button>
                <button className="btn btn-primary" onClick={() => fetchDuplicates(duplicatesPage + 1)} disabled={isLastDuplicateGroup}>Next Group <ChevronRight size={18}/></button>
              </>
            )}

            {/* Upload button logic */}
            {(pathname === '/' || pathname.startsWith('/albums/') || isSpacesPage) && (
              <button className="btn btn-primary" onClick={handleUploadClick}><Upload size={18} /> Upload</button>
            )}

            {/* Create Share Link button for albums */}
            {pathname.startsWith('/albums/') && albumId && (
              <button className="btn" onClick={() => setShowCreateShareLinkModal(albumId)}><Link size={18} /> Create Share Link</button>
            )}
          </>
        )}
      </div>
      <input id="fIn" type="file" multiple onChange={handleUpload} style={{ display: 'none' }} ref={fileInputRef} />
    </header>
  );
}