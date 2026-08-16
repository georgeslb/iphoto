import React from 'react';
import { useLocale } from '../LocaleContext.jsx';
import CreateEntityModal from './CreateEntityModal';

function CreateSpaceModal({
  show,
  onClose,
  newSpaceName,
  setNewSpaceName,
  newSpaceDescription,
  setNewSpaceDescription,
  spaceCreationError,
  handleCreateSpace
}) {
  const { messages } = useLocale();

  return (
    <CreateEntityModal
      show={show}
      onClose={onClose}
      title={messages["createNewSpaceTitle"]}
      name={newSpaceName}
      setName={setNewSpaceName}
      namePlaceholder={messages["spaceNamePlaceholder"]}
      description={newSpaceDescription}
      setDescription={setNewSpaceDescription}
      descriptionPlaceholder={messages["spaceDescriptionPlaceholder"]}
      error={spaceCreationError}
      onSubmit={handleCreateSpace}
    />
  );
}

export default CreateSpaceModal;
