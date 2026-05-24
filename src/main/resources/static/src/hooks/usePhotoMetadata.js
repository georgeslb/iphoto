import { useState, useEffect } from 'react';

export function usePhotoMetadata(selectedPhoto) {
  const [extMetadata, setExtMetadata] = useState(null);

  useEffect(() => {
    if (selectedPhoto) {
      setExtMetadata(null); // Reset metadata when selectedPhoto changes
      fetch(`/api/photos/${selectedPhoto.id}/metadata`)
        .then(res => res.ok ? res.json() : null)
        .then(setExtMetadata)
        .catch(err => console.error("Failed to fetch metadata:", err));
    } else {
      setExtMetadata(null); // Clear metadata if no photo is selected
    }
  }, [selectedPhoto]);

  return extMetadata;
}