import { useState, useEffect, useCallback } from 'react';
import { useLocale } from '../LocaleContext.jsx';

export function useSpaceManagement(selectedMenu, setSelectedSpaceIds) {
  const { messages } = useLocale();
  const [spaces, setSpaces] = useState([]);
  const [garbageSpaces, setGarbageSpaces] = useState([]);
  const [newSpaceName, setNewSpaceName] = useState('');
  const [newSpaceDescription, setNewSpaceDescription] = useState('');
  const [spaceCreationError, setSpaceCreationError] = useState('');
  const [showCreateSpaceModal, setShowCreateSpaceModal] = useState(false);

  const fetchSpaces = useCallback(async () => {
    try {
      const response = await fetch('/api/spaces');
      if (response.ok) {
        const data = await response.json();
        setSpaces(data);
      } else {
        console.error('Failed to fetch spaces');
      }
    } catch (error) {
      console.error('Error fetching spaces:', error);
    }
  }, []);

  const fetchGarbageSpaces = useCallback(async () => {
    try {
      const response = await fetch('/api/spaces/garbage');
      if (response.ok) {
        const data = await response.json();
        setGarbageSpaces(Array.isArray(data.content) ? data.content : []);
      } else {
        console.error('Failed to fetch garbage spaces');
        setGarbageSpaces([]);
      }
    } catch (error) {
      console.error('Error fetching garbage spaces:', error);
      setGarbageSpaces([]);
    }
  }, []);

  useEffect(() => {
    if (selectedMenu === 'spaces')
      fetchSpaces();
  }, [selectedMenu, fetchSpaces]);

  useEffect(() => {
    if (selectedMenu === 'garbage')
      fetchGarbageSpaces();
  }, [selectedMenu, fetchGarbageSpaces]);

  const handleCreateSpace = useCallback(async () => {
    setSpaceCreationError('');
    try {
      const response = await fetch('/api/spaces', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: newSpaceName, description: newSpaceDescription }),
      });
      if (response.ok) {
        setNewSpaceName('');
        setNewSpaceDescription('');
        setShowCreateSpaceModal(false);
        fetchSpaces();
      } else {
        const errorData = await response.json();
        setSpaceCreationError(errorData.message || 'Failed to create space.');
      }
    } catch (error) {
      console.error('Error creating space:', error);
      setSpaceCreationError('An unexpected error occurred.');
    }
  }, [newSpaceName, newSpaceDescription, fetchSpaces]);

  const handleDeleteSpaces = useCallback(async (idsToDelete) => {
    if (!window.confirm(messages["deleteSpacesConfirmation"](idsToDelete.length))) {
      return;
    }
    try {
      const responses = await Promise.all(
        idsToDelete.map(id => fetch(`/api/spaces/${id}`, { method: 'DELETE' }))
      );
      if (responses.every(r => r.ok)) {
        setSelectedSpaceIds([]);
        await fetchSpaces();
      } else {
        throw new Error(messages["oneOrMoreSpacesFailedToDelete"]);
      }
    } catch (error) {
      console.error('Error deleting spaces:', error);
    }
  }, [fetchSpaces, messages, setSelectedSpaceIds]);

  return {
    spaces,
    garbageSpaces,
    newSpaceName,
    setNewSpaceName,
    newSpaceDescription,
    setNewSpaceDescription,
    spaceCreationError,
    showCreateSpaceModal,
    setShowCreateSpaceModal,
    fetchSpaces,
    fetchGarbageSpaces,
    handleCreateSpace,
    handleDeleteSpaces,
  };
}
