import { useState, useEffect, useCallback } from 'react';

export function useAlbumManagement(effectivePath, isLightboxOpen, selectedIds, setSelectedIds, refreshAndMaintainScroll) {
  const [albums, setAlbums] = useState([]);
  const [showAddToAlbumModal, setShowAddToAlbumModal] = useState(false);
  const [targetAlbumIds, setTargetAlbumIds] = useState([]);
  const [newAlbumName, setNewAlbumName] = useState('');
  const [newAlbumDescription, setNewAlbumDescription] = useState('');
  const [albumCreationError, setAlbumCreationError] = useState(null);
  const [showCreateAlbumModal, setShowCreateAlbumModal] = useState(false);

  const fetchAlbums = useCallback(async () => {
    try {
      const res = await fetch('/api/albums');
      const data = await res.json();
      setAlbums(data);
    } catch (err) {
      console.error("Fetch albums failed:", err);
    }
  }, []);

  // Function to fetch albums a photo belongs to
  const fetchAlbumsForPhotos = useCallback(async (photoIds) => {
    if (!photoIds || photoIds.length !== 1) return [];
    const photoId = photoIds[0];
    try {
      const res = await fetch(`/api/photos/${photoId}/albums`, {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' },
      });
      if (!res.ok) throw new Error(`Failed to fetch albums for photos: ${res.status}`);
      const data = await res.json();
      return data.map(album => album.id);
    } catch (err) {
      console.error("Fetch albums for photos failed:", err);
      return [];
    }
  }, []);

  useEffect(() => {
    // Only fetch albums if we are on an album route and NOT on an asset route
    if (effectivePath.startsWith('/albums') && !isLightboxOpen) {
      fetchAlbums();
    }
  }, [effectivePath, fetchAlbums, isLightboxOpen]);

  useEffect(() => {
    if (showAddToAlbumModal) {
      // Fetch all albums
      fetchAlbums();

      // If there are selected photos, pre-select albums they already belong to
      if (selectedIds && selectedIds.length > 0) {
        fetchAlbumsForPhotos(selectedIds).then(preselectedAlbumIds => {
          // Ensure preselectedAlbumIds is an array before setting state
          setTargetAlbumIds(Array.isArray(preselectedAlbumIds) ? preselectedAlbumIds : []);
        });
      } else {
        setTargetAlbumIds([]); // Reset if no photos are selected
      }
    } else {
      // Reset targetAlbumIds when modal closes
      setTargetAlbumIds([]);
    }
  }, [showAddToAlbumModal, fetchAlbums, fetchAlbumsForPhotos, selectedIds]);


  const handleCreateAlbum = useCallback(async () => {
    setAlbumCreationError(null);
    if (!newAlbumName.trim()) {
      setAlbumCreationError("Album name cannot be empty.");
      return;
    }
    try {
      const res = await fetch('/api/albums', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: newAlbumName, description: newAlbumDescription })
      });
      if (!res.ok) throw new Error(`Failed to create album: ${res.status}`);
      setNewAlbumName('');
      setNewAlbumDescription('');
      setShowCreateAlbumModal(false);
      fetchAlbums();
    } catch (err) {
      setAlbumCreationError(err.message);
      console.error("Error creating album:", err);
    }
  }, [newAlbumName, newAlbumDescription, fetchAlbums]);

  const handleAddPhotosToAlbums = useCallback(async () => {
    if (selectedIds.length === 0 || targetAlbumIds.length === 0) return;

    const addPromises = targetAlbumIds.map(albumId =>
        fetch(`/api/albums/${albumId}/photos`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ photoIds: selectedIds })
        })
    );

    try {
      const responses = await Promise.all(addPromises);
      const allOk = responses.every(res => res.ok);

      if (allOk) {
        alert(`Added ${selectedIds.length} photo(s) to ${targetAlbumIds.length} album(s).`);
        setSelectedIds([]);
        setShowAddToAlbumModal(false);
        setTargetAlbumIds([]); // Clear selections on successful add
        await fetchAlbums();
      } else {
        const errorDetails = await Promise.all(responses.map(res => res.json().catch(() => ({ message: res.statusText }))));
        console.error('handleAddPhotosToAlbums: One or more requests failed:', errorDetails); // More detailed error
        throw new Error('One or more requests to add photos failed.');
      }
    } catch (err) {
      alert(`Error: ${err.message}`);
    }
  }, [selectedIds, targetAlbumIds, setSelectedIds, fetchAlbums, setShowAddToAlbumModal, setTargetAlbumIds]);

  const handleRemovePhotosFromAlbum = useCallback(async (albumId) => {
    if (!albumId || selectedIds.length === 0) return;
    if (!window.confirm(`Remove ${selectedIds.length} photo(s) from this album?`)) return;

    try {
      const res = await fetch(`/api/albums/${albumId}/photos`, {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ photoIds: selectedIds })
      });
      if (res.ok) {
        setSelectedIds([]);
        // Use refreshAndMaintainScroll if it's provided and not null
        if (refreshAndMaintainScroll) {
          await refreshAndMaintainScroll();
        } else {
          await fetchAlbums(); // Fallback if refreshAndMaintainScroll is not ready
        }
      } else throw new Error(`Failed to remove photos: ${res.status}`);
    } catch (err) {
      alert(`Error removing photos: ${err.message}`);
    }
  }, [selectedIds, setSelectedIds, refreshAndMaintainScroll, fetchAlbums]);

  const handleDeleteAlbums = useCallback(async (ids) => {
    if (!window.confirm(`Delete ${ids.length} album(s)? The photos inside them will not be deleted.`)) return;

    try {
      const deletePromises = ids.map(id => fetch(`/api/albums/${id}`, { method: 'DELETE' }));
      const responses = await Promise.all(deletePromises);
      if (responses.every(r => r.ok)) {
        setSelectedIds([]);
        await fetchAlbums();
      } else {
        throw new Error("One or more albums failed to delete");
      }
    } catch (err) {
      alert(`Error deleting albums: ${err.message}`);
    }
  }, [setSelectedIds, fetchAlbums]);

  const handleSetCoverPhoto = useCallback(async (albumId, photoId) => {
    if (!albumId || !photoId) return;
    try {
      const res = await fetch(`/api/albums/${albumId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ coverPhotoId: photoId })
      });
      if (res.ok) {
        setSelectedIds([]);
        await fetchAlbums();
      } else throw new Error(`Failed to set cover photo: ${res.status}`);
    } catch (err) {
      alert(`Error setting cover photo: ${err.message}`);
    }
  }, [setSelectedIds, fetchAlbums]);

  return {
    albums,
    setAlbums,
    showCreateAlbumModal,
    setShowCreateAlbumModal,
    showAddToAlbumModal,
    setShowAddToAlbumModal,
    targetAlbumIds,
    setTargetAlbumIds,
    newAlbumName,
    setNewAlbumName,
    newAlbumDescription,
    setNewAlbumDescription,
    albumCreationError,
    setAlbumCreationError,
    fetchAlbums,
    handleCreateAlbum,
    handleAddPhotosToAlbums,
    handleRemovePhotosFromAlbum,
    handleDeleteAlbums,
    handleSetCoverPhoto,
  };
}