import { useState, useCallback } from 'react';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

export function useShareLinkManagement() {
  const { messages } = useLocale(); // Use the useLocale hook
  const [showCreateShareLinkModal, setShowCreateShareLinkModal] = useState(false);
  const [albumIdForShareLink, setAlbumIdForShareLink] = useState(null);
  const [newShareLinkName, setNewShareLinkName] = useState('');
  const [newShareLinkPassword, setNewShareLinkPassword] = useState('');
  const [newShareLinkExpiresInDays, setNewShareLinkExpiresInDays] = useState(7);
  const [shareLinkCreationError, setShareLinkCreationError] = useState(null);

  const handleCreateShareLink = useCallback(async () => {
    setShareLinkCreationError(null);
    if (!newShareLinkName.trim()) {
      setShareLinkCreationError(messages["linkNameEmptyError"]);
      return;
    }
    const urlFriendlyRegex = /^[a-zA-Z0-9_-]+$/;
    if (!urlFriendlyRegex.test(newShareLinkName)) {
      setShareLinkCreationError(messages["linkNameInvalidError"]);
      return;
    }
    if (newShareLinkExpiresInDays <= 0) {
      setShareLinkCreationError(messages["linkAvailabilityError"]);
      return;
    }
    if (!albumIdForShareLink) {
      setShareLinkCreationError(messages["noAlbumSelectedError"]);
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
        throw new Error(errorData.message || messages["failedToCreateShareLink"](res.status));
      }
      alert(messages["shareLinkCreatedSuccess"](albumIdForShareLink, newShareLinkName));
      setNewShareLinkName('');
      setNewShareLinkPassword('');
      setNewShareLinkExpiresInDays(7);
      setAlbumIdForShareLink(null);
      setShowCreateShareLinkModal(false);
    } catch (err) {
      setShareLinkCreationError(err.message);
      console.error(messages["errorCreatingShareLink"], err);
    }
  }, [newShareLinkName, newShareLinkPassword, newShareLinkExpiresInDays, albumIdForShareLink, messages]);

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