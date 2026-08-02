import React, { useState, useEffect, useCallback } from 'react';
import { Image as ImageIcon, Check, Plus, Circle, Folder, Trash2 } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import CreateSpaceModal from './CreateSpaceModal'; // We'll create this next
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

function SpaceGridDisplay({
  selectedIds,
  setSelectedIds,
  newSpaceName,
  setNewSpaceName,
  newSpaceDescription,
  setNewSpaceDescription,
  spaceCreationError,
  setSpaceCreationError,
  handleCreateSpace,
  showCreateSpaceModal,
  setShowCreateSpaceModal,
  spaces,
  fetchSpaces,
  handleDeleteSpaces,
}) {
  const { messages } = useLocale(); // Use the useLocale hook
  const navigate = useNavigate();

  useEffect(() => {
    fetchSpaces();
  }, [fetchSpaces]);

  return (
    <div className="content-body">
      <div style={{ marginBottom: '20px', display: 'flex', justifyContent: 'flex-start', marginTop: '10px' }}>
          <button className="btn btn-primary" onClick={() => setShowCreateSpaceModal(true)}>
              <Plus size={18}/> {messages["createNewSpaceTitle"]}
          </button>
          {selectedIds.length > 0 && (
            <button className="btn" style={{marginLeft: '10px', backgroundColor: '#ff3b30', color: 'white'}} onClick={() => handleDeleteSpaces(selectedIds)}>
              <Trash2 size={18}/> {messages["deleteSelectedSpaces"]}
            </button>
          )}
      </div>

      <div className="album-grid"> {/* Reusing album-grid styling for now */}
        {spaces.map(space => {
          const isSelected = selectedIds.includes(space.id);
          return (
            <div
              key={space.id}
              className={`album-card ${isSelected ? 'selected' : ''}`} // Reusing album-card styling
              onClick={() => {
                if (selectedIds.length > 0) {
                  setSelectedIds(v => v.includes(space.id) ? v.filter(i => i !== space.id) : [...v, space.id]);
                } else {
                  navigate(`/spaces/${space.id}`);
                }
              }}
            >
              <div
                className="select-overlay"
                onClick={(e) => {
                  e.stopPropagation();
                  setSelectedIds(v => v.includes(space.id) ? v.filter(i => i !== space.id) : [...v, space.id]);
                }}
              >
                <div className={`check-circle ${isSelected ? 'checked' : ''}`}>
                  {isSelected ? <Check size={14} /> : <Circle size={14} color="#ccc" />}
                </div>
              </div>

              <div className="album-cover"> {/* Always show folder icon for spaces */}
                <div className="album-placeholder"><Folder size={48} color="#888" /></div>
              </div>
              <div className="album-info"> {/* Reusing album-info styling */}
                <h3>{space.name}</h3>
                <p>{messages["photosCount"](space.photoCount)}</p>
              </div>
            </div>
          );
        })}
        {spaces.length === 0 && ( <div style={{textAlign: 'center', padding: '50px', color: '#888', gridColumn: '1/-1'}}> {messages["noSpacesCreatedYet"]} </div>)}
      </div>

      <CreateSpaceModal
        show={showCreateSpaceModal}
        onClose={() => setShowCreateSpaceModal(false)}
        newSpaceName={newSpaceName}
        setNewSpaceName={setNewSpaceName}
        newSpaceDescription={newSpaceDescription}
        setNewSpaceDescription={setNewSpaceDescription}
        spaceCreationError={spaceCreationError}
        handleCreateSpace={handleCreateSpace}
      />
    </div>
  )
}

export default SpaceGridDisplay;