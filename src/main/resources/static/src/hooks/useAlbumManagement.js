import { useState, useEffect, useCallback } from 'react';

export function useAlbumManagement(effectivePath, isLightboxOpen, selectedIds, setSelectedIds, refreshAndMaintainScroll) {
  const [albums, setAlbums] = useState([]);
  const [showAddToAlbumModal, setShowAddToAlbumModal] = useState(false);
  const [targetAlbumIds, setTargetAlbumIds] = useState([]);
  const [initialTargetAlbumIds, setInitialTargetAlbumIds] = useState([]); /* New state for initially selected albums */
  const [newAlbumName, setNewAlbumName] = useState('');
  const [newAlbumDescription, setNewAlbumDescription] = useState('');
  const [albumCreationError, setAlbumCreationError] = useState(null);
  const [showCreateAlbumModal, setShowCreateAlbumModal] = useState(false);
  const [showRemoveFromAlbumModal, setShowRemoveFromAlbumModal] = useState(false);
  const [albumsPhotoBelongsTo, setAlbumsPhotoBelongsTo] = useState([]);

  const fetchAlbums = useCallback(async () => {
    try {
      const res = await fetch('/api/albums');
      const data = await res.json();
      setAlbums(data);
    } catch (err) {
      console.error("Fetch albums failed:", err);
    }
  }, []);

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
      return data;
    } catch (err) {
      console.error("Fetch albums for photos failed:", err);
      return [];
    }
  }, []);

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
        setSelectedIds([]);
        setShowAddToAlbumModal(false);
        setTargetAlbumIds([]);
        await fetchAlbums();
      } else {
        const errorDetails = await Promise.all(responses.map(res => res.json().catch(() => ({ message: res.statusText }))));
        console.error('handleAddPhotosToAlbums: One or more requests failed:', errorDetails);
        throw new Error('One or more requests to add photos failed.');
      }
    } catch (err) {
      console.error(`Error adding photos to albums: ${err.message}`);
    }
  }, [selectedIds, targetAlbumIds, setSelectedIds, fetchAlbums, setShowAddToAlbumModal, setTargetAlbumIds]);

  useEffect(() => {
    if (effectivePath.startsWith('/albums') && !isLightboxOpen) {
      fetchAlbums();
    }
  }, [effectivePath, fetchAlbums, isLightboxOpen]);

  useEffect(() => {
    if (showAddToAlbumModal) {
      fetchAlbums();

      if (selectedIds && selectedIds.length > 0) {
        fetchAlbumsForPhotos(selectedIds).then(preselectedAlbumObjects => {
          const ids = Array.isArray(preselectedAlbumObjects) ? preselectedAlbumObjects.map(a => a.id) : [];
          setTargetAlbumIds(ids);
          setInitialTargetAlbumIds(ids); /* Store initial selection */
        });
      } else {
        setTargetAlbumIds([]);
        setInitialTargetAlbumIds([]); /* Reset initial selection */
      }
    } else {
      setTargetAlbumIds([]);
      setInitialTargetAlbumIds([]); /* Reset initial selection */
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


  const handleRemovePhotosFromAlbum = useCallback(async (albumId, photoIdsToRemove = selectedIds) => {
    if (!albumId || photoIdsToRemove.length === 0) return;

    try {
      const res = await fetch(`/api/albums/${albumId}/photos`, {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ photoIds: photoIdsToRemove })
      });
      if (res.ok) {
        if (photoIdsToRemove === selectedIds) {
          setSelectedIds([]);
        }
        if (refreshAndMaintainScroll) {
          await refreshAndMaintainScroll();
        } else {
          await fetchAlbums();
        }
      } else throw new Error(`Failed to remove photos: ${res.status}`);
    } catch (err) {
      console.error(`Error removing photos: ${err.message}`);
    }
  }, [selectedIds, setSelectedIds, refreshAndMaintainScroll, fetchAlbums]);

  const handleDeleteAlbums = useCallback(async (ids) => {
    if (!ids || ids.length === 0) return;

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
      console.error(`Error deleting albums: ${err.message}`);
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
      console.error(`Error setting cover photo: ${err.message}`);
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
    initialTargetAlbumIds, /* New */
    newAlbumName,
    setNewAlbumName,
    newAlbumDescription,
    setNewAlbumDescription,
    albumCreationError,
    setAlbumCreationError,
    fetchAlbums,
    fetchAlbumsForPhotos,
    handleCreateAlbum,
    handleAddPhotosToAlbums,
    handleRemovePhotosFromAlbum,
    handleDeleteAlbums,
    handleSetCoverPhoto,
    showRemoveFromAlbumModal,
    setShowRemoveFromAlbumModal,
    albumsPhotoBelongsTo,
    setAlbumsPhotoBelongsTo,
  };
}