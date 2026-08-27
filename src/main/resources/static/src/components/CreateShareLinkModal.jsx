import React, { useEffect } from 'react';
import { useLocale } from '../LocaleContext.jsx';

// Turns an album name into a URL-friendly slug (matches the a-z/0-9/-/_ pattern
// useShareLinkManagement.js validates share link names against).
function slugify(str) {
  return (str || '')
    .normalize('NFD').replace(/[\u0300-\u036f]/g, '') // strip accents
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

function CreateShareLinkModal({
  show,
  onClose,
  albumIdForShareLink,
  albums,
  newShareLinkName,
  setNewShareLinkName,
  newShareLinkPassword,
  setNewShareLinkPassword,
  newShareLinkExpiresInDays,
  setNewShareLinkExpiresInDays,
  shareLinkCreationError,
  handleCreateShareLink
}) {
  const { messages } = useLocale();
  const currentAlbum = albums.find(a => a.id === albumIdForShareLink);

  // Pre-fill the link name from the album name (slugified) so the user usually doesn't have to
  // type one at all — only when the field is still empty, so it never overwrites something the
  // user already typed for this album.
  useEffect(() => {
    if (show && currentAlbum && !newShareLinkName) {
      setNewShareLinkName(slugify(currentAlbum.name));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [show, albumIdForShareLink]);

  if (!show) return null;

  return (
    <div className="modal-overlay">
      <div className="modal-content">
        <h2>{messages["createShareLinkModalTitle"](currentAlbum?.name)}</h2>
        {shareLinkCreationError && <div className="error-message">{shareLinkCreationError}</div>}
        <input
            type="text"
            placeholder={messages["linkNamePlaceholder"]}
            value={newShareLinkName}
            onChange={(e) => setNewShareLinkName(e.target.value)}
            className="modal-input"
        />
        <input
            type="password"
            placeholder={messages["passwordOptionalPlaceholder"]}
            value={newShareLinkPassword}
            onChange={(e) => setNewShareLinkPassword(e.target.value)}
            className="modal-input"
            style={{ marginTop: '10px' }}
        />
        <label className="modal-label" style={{ marginTop: '10px' }}>{messages["availableDaysLabel"]}</label>
        <input
            type="number"
            value={newShareLinkExpiresInDays}
            onChange={(e) => setNewShareLinkExpiresInDays(Math.max(1, parseInt(e.target.value) || 1))}
            className="modal-input"
            min="1"
        />
        <div className="modal-actions">
          <button className="btn" onClick={onClose}>{messages["cancel"]}</button>
          <button className="btn btn-primary" onClick={handleCreateShareLink}>{messages["createLinkButton"]}</button>
        </div>
      </div>
    </div>
  );
}

export default CreateShareLinkModal;
