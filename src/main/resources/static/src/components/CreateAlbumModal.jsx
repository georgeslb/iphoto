import React from 'react';
import { useLocale } from '../LocaleContext.jsx';
import CreateEntityModal from './CreateEntityModal';

function CreateAlbumModal({
  show,
  onClose,
  newAlbumName,
  setNewAlbumName,
  newAlbumDescription,
  setNewAlbumDescription,
  albumCreationError,
  handleCreateAlbum
}) {
  const { messages } = useLocale();

  return (
    <CreateEntityModal
      show={show}
      onClose={onClose}
      title={messages["createNewAlbumTitle"]}
      name={newAlbumName}
      setName={setNewAlbumName}
      namePlaceholder={messages["albumNamePlaceholder"]}
      description={newAlbumDescription}
      setDescription={setNewAlbumDescription}
      descriptionPlaceholder={messages["albumDescriptionPlaceholder"]}
      error={albumCreationError}
      onSubmit={handleCreateAlbum}
    />
  );
}

export default CreateAlbumModal;
