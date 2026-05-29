import React from 'react';
import { X, Upload, Plus, Trash2, RotateCcw, ImageIcon, ArrowLeft, ChevronLeft, ChevronRight, Link } from 'lucide-react';

export default function Header({
  selectedIds,
  setSelectedIds,
  pathname,
  handleRestore,
  handlePermanentDelete,
  handleRemovePhotosFromAlbum,
  handleMoveToGarbage,
  setShowAddToAlbumModal,
  setShowCreateShareLinkModal, // New prop
  handleUpload,
  albumId,
  canSetCoverPhoto,
  handleSetCoverPhoto,
  navigate,
  duplicatesPage,
  fetchDuplicates,
  isLastDuplicateGroup,
  handleDeleteAlbums
}) {
  const selectionMode = selectedIds.length > 0;
  const isDuplicatesPage = pathname.startsWith('/duplicates');
  const isAlbumsMainPage = pathname === '/albums';

  return (
    <header className="header">
      <div>
        {pathname.startsWith('/albums/') && albumId && (
          <button className="btn" onClick={() => navigate('/albums')}><ArrowLeft size={18} /> Back to Albums</button>
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
            ) : (
              <>
                <button className="btn" style={{color: '#ff3b30'}} onClick={() => handleMoveToGarbage(selectedIds)} disabled={selectedIds.length === 0}><Trash2 size={18}/> Move to Garbage</button>
                {pathname === '/' && (
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

            {/* Removed "Create Album" button from here */}
            {pathname.startsWith('/albums/') && albumId ? (
              <>
                <button className="btn btn-primary" onClick={() => document.getElementById('fIn').click()}><Upload size={18} /> Upload</button>
                <button className="btn" onClick={() => setShowCreateShareLinkModal(albumId)}><Link size={18} /> Create Share Link</button>
              </>
            ) : !isDuplicatesPage ? (
              <>
                <button className="btn btn-primary" onClick={() => document.getElementById('fIn').click()}><Upload size={18} /> Upload</button>
              </>
            ) : null}
          </>
        )}
      </div>
      <input id="fIn" type="file" multiple onChange={handleUpload} style={{ display: 'none' }} />
    </header>
  );
}