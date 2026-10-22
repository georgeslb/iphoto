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
  // type one at all. We check existing shares via /api/share/my-shares so that if an album
  // already has a share link with that slug, we automatically append a numeric suffix (-1, -2)
  // instead of pre-filling a duplicate name that would immediately fail with 409 Conflict.
  useEffect(() => {
    if (show && currentAlbum && !newShareLinkName) {
      const base = slugify(currentAlbum.name);
      if (!base) return;
      fetch('/api/share/my-shares')
        .then(res => (res.ok ? res.json() : []))
        .then(shares => {
          const taken = new Set((shares || []).map(s => s.name?.toLowerCase()));
          let candidate = base;
          let counter = 1;
          while (taken.has(candidate.toLowerCase())) {
            candidate = `${base}-${counter}`;
            counter++;
          }
          setNewShareLinkName(candidate);
        })
        .catch(() => {
          setNewShareLinkName(base);
        });
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
