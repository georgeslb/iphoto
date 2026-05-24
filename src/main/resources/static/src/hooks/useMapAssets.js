import { useState, useEffect } from 'react';

export function useMapAssets(effectivePath) {
  const [mapAssets, setMapAssets] = useState([]);

  useEffect(() => {
    if (effectivePath === '/map') {
      fetch('/api/photos/locations')
        .then(r => r.json())
        .then(setMapAssets)
        .catch(err => console.error("Failed to fetch map assets:", err));
    } else {
      setMapAssets([]); // Clear map assets if not on the map page
    }
  }, [effectivePath]);

  return mapAssets;
}