import React, { useCallback } from 'react';
import { X, Upload, Plus, Trash2, RotateCcw, ImageIcon, ArrowLeft, ChevronLeft, ChevronRight, Link, Home, MinusCircle, FolderX,  ArchiveRestore} from 'lucide-react'; // Added Home icon
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

export default function Header({
  selectedIds,
  setSelectedIds,
  selectedSpaceIds,
  selectedAlbumIds,
  setSelectedSpaceIds,
  setSelectedAlbumIds,
  menuSpaceId,
  menuAlbumId,
  pathname,
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
  navigate,
  duplicatesPage,
  fetchDuplicates,
  isLastDuplicateGroup,
  handleDeleteAlbums,
  handleDeleteSpaces,
  setShowCreateAlbumModal,
  setShowCreateSpaceModal,
  fileInputRef,
  photoSelection,
  refreshAndMaintainScroll, // Existing prop
  fromGarbage,
}) {
  const { messages } = useLocale(); // Use the useLocale hook
  const selectionMode = selectedIds.length > 0 || selectedSpaceIds.length > 0 || selectedAlbumIds.length > 0;
  const isDuplicatesPage = pathname.startsWith('/duplicates');
  const selectionCount = pathname === '/spaces' ? selectedSpaceIds.length : pathname === '/albums' ? selectedAlbumIds.length : selectedIds.length
  const isSpacesPage = pathname.startsWith('/spaces/') && menuSpaceId;
  const menuSelection = photoSelection.menuSelection
  const handleUploadClick = useCallback(() => {
    if (fileInputRef.current) {
      fileInputRef.current.dataset.albumId = menuAlbumId || '';
      fileInputRef.current.dataset.spaceId = menuSpaceId || '';
      fileInputRef.current.click();
    }
  }, [menuAlbumId, menuSpaceId, fileInputRef]);

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
        {pathname.startsWith('/albums/') && menuAlbumId && (
          <button className="btn" onClick={() => navigate('/albums')} title={messages["backToAlbums"]}><ArrowLeft size={18} /></button>
        )}
        {isSpacesPage && (
          <button className="btn" onClick={() => navigate(fromGarbage ? '/garbage' : '/spaces')} title={fromGarbage ? messages["backToGarbage"] : messages["backToSpaces"]}>
            <ArrowLeft size={18} />
          </button>
        )}
      </div>

      <div className="header-actions" style={{ display: 'flex', gap: '12px', alignItems: 'center' }}>
        {selectionMode && (
          <>
            <button className="btn" onClick={() => {setSelectedIds([]); setSelectedAlbumIds([]); setSelectedSpaceIds([])}} title={messages["cancel"]}><X size={18}/></button>
            <span style={{fontWeight: 600}}>{messages["selectedCount"](selectionCount)}</span>
            {photoSelection.canBeRestored && <button className="btn" style={{color: '#34c759'}} onClick={() => handleRestore(selectedIds)} title={messages["restore"]}><RotateCcw size={18}/></button>}
            {menuSelection.canRestoreSpace && <button className="btn" style={{color: '#34c759'}} onClick={() => handleRestoreSpaces(selectedSpaceIds)} title={messages["restore"]}><ArchiveRestore size={18}/></button>}
            {photoSelection.canBePermanentlyDeleted && <button className="btn" style={{color: '#ff3b30'}} onClick={() => handlePermanentDelete(selectedIds)} title={messages["deletePermanently"]}><Trash2 size={18}/></button>}
            {menuSelection.canPermanentlyDeleteSpace && <button className="btn" style={{color: '#ff3b30'}} onClick={() => handlePermanentDeleteSpaces(selectedSpaceIds)} title={messages["deletePermanently"]}><FolderX  size={18}/></button>}
            {photoSelection.canBeSetAsCover && <button className="btn btn-primary" onClick={() => handleSetCoverPhoto(menuAlbumId, selectedIds[0])} title={messages["setAsCover"]}><ImageIcon size={18}/></button>}
            {photoSelection.canBeRemovedFromAlbum && <button className="btn" style={{color: '#ff3b30'}} onClick={() => handleRemovePhotosFromAlbum(menuAlbumId)} title={messages["removeFromAlbum"]}><MinusCircle size={18}/></button>}
            {menuSelection.canDeleteAlbum && <button className="btn" style={{color: '#ff3b30'}} onClick={() => handleDeleteAlbums(selectedAlbumIds)} title={messages["deleteAlbums"]}><Trash2 size={18}/></button>}
            {menuSelection.canDeleteSpace && <button className="btn" style={{color: '#ff3b30'}} onClick={() => handleDeleteSpaces(selectedSpaceIds)} title={messages["deleteSelectedSpaces"]}><FolderX  size={18}/></button>}
            {photoSelection.canBeMovedToMain && <button className="btn" style={{backgroundColor: '#28a745', color: 'white'}} onClick={handleMoveToMain} title={messages["moveToMain"]}><Home size={18}/></button>}
            {photoSelection.canBeMovedToAlbum && <button className="btn btn-primary" onClick={() => setShowAddToAlbumModal(true)} title={messages["addToAlbum"]}><Plus size={18}/></button>}
            {photoSelection.canBeDeleted && <button className="btn" style={{color: '#ff3b30'}} onClick={() => handleMoveToGarbage(selectedIds)} title={messages["moveToGarbage"]}><Trash2 size={18}/></button>}

          </>
        )}

        {
          <>
            {menuSelection.canUpload && (
                <button className="btn btn-primary" onClick={handleUploadClick} title={messages["upload"]}><Upload
                    size={18}/></button>
            )}
            {menuSelection.canCreateAlbum && (
                <button className="btn btn-primary" onClick={() => setShowCreateAlbumModal(true)}
                        title={messages["createNewAlbumTitle"]}>
                  <Plus size={18}/></button>

            )}
            {menuSelection.canCreateSpace && (
                <button className="btn btn-primary" onClick={() => setShowCreateSpaceModal(true)} title={messages["createNewSpaceTitle"]}>
                  <Plus size={18}/>
                </button>
            )}
            {menuSelection.canAddAAShareLinkToAlbum && (
                <button className="btn" onClick={() => setShowCreateShareLinkModal(menuAlbumId)} title={messages["createShareLink"]}><Link size={18} /></button>
            )}
          </>
        }

        {/* Duplicates navigation buttons, always visible if on duplicates page and not in selection mode */}
        {!selectionMode && pathname.startsWith('/duplicates') && (
          <>
            <button className="btn" onClick={() => fetchDuplicates(duplicatesPage - 1)} disabled={duplicatesPage === 0} title={messages["prevGroup"]}><ChevronLeft size={18}/></button>
            <button className="btn btn-primary" onClick={() => fetchDuplicates(duplicatesPage + 1)} disabled={isLastDuplicateGroup} title={messages["nextGroup"]}><ChevronRight size={18}/></button>
          </>
        )}
      </div>
      <input id="fIn" type="file" multiple accept="image/*,video/*" onChange={handleUpload} style={{ display: 'none' }} ref={fileInputRef} />
    </header>
  );
}