import { useState, useCallback } from 'react';

export function useShareLinkManagement() {
  const [showCreateShareLinkModal, setShowCreateShareLinkModal] = useState(false);
  const [albumIdForShareLink, setAlbumIdForShareLink] = useState(null);
  const [newShareLinkName, setNewShareLinkName] = useState('');
  const [newShareLinkPassword, setNewShareLinkPassword] = useState('');
  const [newShareLinkExpiresInDays, setNewShareLinkExpiresInDays] = useState(7);
  const [shareLinkCreationError, setShareLinkCreationError] = useState(null);

  const handleCreateShareLink = useCallback(async () => {
    setShareLinkCreationError(null);
    if (!newShareLinkName.trim()) {
      setShareLinkCreationError("Link name cannot be empty.");
      return;
    }
    const urlFriendlyRegex = /^[a-zA-Z0-9_-]+$/;
    if (!urlFriendlyRegex.test(newShareLinkName)) {
      setShareLinkCreationError("Link name can only contain letters, numbers, hyphens, and underscores.");
      return;
    }
    if (newShareLinkExpiresInDays <= 0) {
      setShareLinkCreationError("Link availability must be at least 1 day.");
      return;
    }
    if (!albumIdForShareLink) {
      setShareLinkCreationError("No album selected for sharing.");
      return;
    }

    try {
      const res = await fetch('/api/share', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: newShareLinkName,
          password: newShareLinkPassword,
          daysAvailable: newShareLinkExpiresInDays,
          albumId: albumIdForShareLink
        })
      });
      if (!res.ok) {
        const errorData = await res.json();
        throw new Error(errorData.message || `Failed to create share link: ${res.status}`);
      }
      alert(`Share link "${albumIdForShareLink}"/"${newShareLinkName}" created successfully!`);
      setNewShareLinkName('');
      setNewShareLinkPassword('');
      setNewShareLinkExpiresInDays(7);
      setAlbumIdForShareLink(null);
      setShowCreateShareLinkModal(false);
    } catch (err) {
      setShareLinkCreationError(err.message);
      console.error("Error creating share link:", err);
    }
  }, [newShareLinkName, newShareLinkPassword, newShareLinkExpiresInDays, albumIdForShareLink]);

  const handleShowCreateShareLinkModal = useCallback((albumId) => {
    setAlbumIdForShareLink(albumId);
    setShowCreateShareLinkModal(true);
  }, []);

  return {
    showCreateShareLinkModal,
    setShowCreateShareLinkModal,
    albumIdForShareLink,
    setAlbumIdForShareLink,
    newShareLinkName,
    setNewShareLinkName,
    newShareLinkPassword,
    setNewShareLinkPassword,
    newShareLinkExpiresInDays,
    setNewShareLinkExpiresInDays,
        shareLinkCreationError,
    setShareLinkCreationError,
    handleCreateShareLink,
    handleShowCreateShareLinkModal,
  };
}