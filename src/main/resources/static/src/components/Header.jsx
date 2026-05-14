import React from 'react';
import { X, Upload, Plus, Trash2, RotateCcw, ImageIcon, ArrowLeft } from 'lucide-react';

export default function Header({
  selectedIds,
  setSelectedIds,
  pathname,
  handleRestore,
  handlePermanentDelete,
  handleRemovePhotosFromAlbum,
  handleMoveToGarbage,
  setShowAddToAlbumModal,
  setShowCreateAlbumModal,
  handleUpload,
  albumId,
  canSetCoverPhoto,
  handleSetCoverPhoto,
  navigate
}) {
  const selectionMode = selectedIds.length > 0;

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
            ) : (
              <>
                <button className="btn" style={{color: '#ff3b30'}} onClick={() => handleMoveToGarbage(selectedIds)} disabled={selectedIds.length === 0}><Trash2 size={18}/> Move to Garbage</button>
                <button className="btn btn-primary" onClick={() => setShowAddToAlbumModal(true)} disabled={selectedIds.length === 0}><Plus size={18}/> Add to Album</button>
              </>
            )}
          </>
        ) : (
          <>
            {pathname === '/albums' && !albumId ? (
              <button className="btn btn-primary" onClick={() => setShowCreateAlbumModal(true)}><Plus size={18} /> Create Album</button>
            ) : pathname.startsWith('/albums/') && albumId ? (
              <button className="btn btn-primary" onClick={() => document.getElementById('fIn').click()}><Upload size={18} /> Upload</button>
            ) : (
              <>
                <button className="btn btn-primary" onClick={() => document.getElementById('fIn').click()}><Upload size={18} /> Upload</button>
              </>
            )}
          </>
        )}
      </div>
      <input id="fIn" type="file" multiple onChange={handleUpload} style={{ display: 'none' }} />
    </header>
  );
}
