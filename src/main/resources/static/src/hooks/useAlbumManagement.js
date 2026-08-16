import { useState, useEffect, useCallback } from 'react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

export function useAlbumManagement(effectivePath, isLightboxOpen, selectedIds, setSelectedIds, refreshAndMaintainScroll) {
  const { messages } = useLocale(); // Use the useLocale hook
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
      console.error("Fetch albums failed:", err); // This message is not in i18n, keeping as is
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
      if (!res.ok) throw new Error(messages["failedToFetchAlbumsForPhotos"](res.status));
      const data = await res.json();
      return data;
    } catch (err) {
      console.error(messages["fetchAlbumsForPhotosFailed"], err);
      return [];
    }
  }, [messages]);

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
        console.error('handleAddPhotosToAlbums: One or more requests failed:', errorDetails); // Not in i18n
        throw new Error(messages["addPhotosFailed"]);
      }
    } catch (err) {
      console.error(messages["errorAddingPhotosToAlbums"](err.message));
    }
  }, [selectedIds, targetAlbumIds, setSelectedIds, fetchAlbums, setShowAddToAlbumModal, setTargetAlbumIds, messages]);

  useEffect(() => {
    if (effectivePath === '/albums') {
      fetchAlbums();
    }
  }, [effectivePath, fetchAlbums]);

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
      setAlbumCreationError(messages["albumNameEmptyError"]);
      return;
    }
    try {
      const res = await fetch('/api/albums', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: newAlbumName, description: newAlbumDescription })
      });
      if (!res.ok) throw new Error(messages["failedToCreateAlbum"](res.status));
      setNewAlbumName('');
      setNewAlbumDescription('');
      setShowCreateAlbumModal(false);
      fetchAlbums();
    } catch (err) {
      setAlbumCreationError(err.message);
      console.error(messages["errorCreatingAlbum"], err);
    }
  }, [newAlbumName, newAlbumDescription, fetchAlbums, messages]);


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
      } else throw new Error(messages["failedToRemovePhotos"](res.status));
    } catch (err) {
      console.error(messages["errorRemovingPhotos"](err.message));
    }
  }, [selectedIds, setSelectedIds, refreshAndMaintainScroll, fetchAlbums, messages]);

  const handleDeleteAlbums = useCallback(async (ids) => {
    if (!ids || ids.length === 0) return;

    try {
      const deletePromises = ids.map(id => fetch(`/api/albums/${id}`, { method: 'DELETE' }));
      const responses = await Promise.all(deletePromises);
      if (responses.every(r => r.ok)) {
        setSelectedIds([]);
        await fetchAlbums();
      } else {
        throw new Error(messages["oneOrMoreAlbumsFailedToDelete"]);
      }
    } catch (err) {
      console.error(messages["errorDeletingAlbums"](err.message));
    }
  }, [setSelectedIds, fetchAlbums, messages]);

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
      } else throw new Error(messages["failedToSetCoverPhoto"](res.status));
    } catch (err) {
      console.error(messages["errorSettingCoverPhoto"](err.message));
    }
  }, [setSelectedIds, fetchAlbums, messages]);

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