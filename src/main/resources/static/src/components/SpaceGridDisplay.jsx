import React, { useEffect } from 'react';
import { Folder } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { useLocale } from '../LocaleContext.jsx';
import EntityGridDisplay from './EntityGridDisplay';

function SpaceGridDisplay({
  selectedSpaceIds,
  setSelectedSpaceIds,
  setSelectedIds,
  spaces,
  fetchSpaces, selectionMenu
}) {
  const { messages } = useLocale();
  const navigate = useNavigate();
  const isGarbage = selectionMenu === 'garbage';

  useEffect(() => {
    fetchSpaces();
  }, [fetchSpaces]);

  const toggle = (id) => {
    setSelectedSpaceIds(v => v.includes(id) ? v.filter(i => i !== id) : [...v, id]);
    if (isGarbage) setSelectedIds([]);
  };

  return (
    <EntityGridDisplay
      items={spaces.map(space => ({ ...space, photoCountLabel: messages["photosCount"](space.photoCount) }))}
      selectedIds={selectedSpaceIds}
      onToggleSelect={toggle}
      onOpen={(space) => navigate(`/spaces/${space.id}`, { state: { fromGarbage: isGarbage } })}
      renderCover={() => (
        <div className="album-cover">
          <div className="album-placeholder"><Folder size={48} color="#888" /></div>
        </div>
      )}
      emptyMessage={messages["noSpacesCreatedYet"]}
    />
  )
}

export default SpaceGridDisplay;
