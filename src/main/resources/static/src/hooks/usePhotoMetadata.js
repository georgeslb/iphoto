import { useState, useEffect } from 'react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

export function usePhotoMetadata(selectedPhoto) {
  const { messages } = useLocale(); // Use the useLocale hook
  const [extMetadata, setExtMetadata] = useState(null);

  useEffect(() => {
    if (selectedPhoto) {
      setExtMetadata(null); // Reset metadata when selectedPhoto changes
      fetch(`/api/photos/${selectedPhoto.id}/metadata`)
        .then(res => res.ok ? res.json() : null)
        .then(setExtMetadata)
        .catch(err => console.error(messages["failedToFetchMetadata"], err));
    } else {
      setExtMetadata(null); // Clear metadata if no photo is selected
    }
  }, [selectedPhoto, messages]);

  return extMetadata;
}