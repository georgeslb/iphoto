import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Lock } from 'lucide-react';
import PhotoGrid from './PhotoGrid';
import SharedLightbox from './SharedLightbox';
import { useLocale } from '../LocaleContext.jsx';
import { useSharedAlbumPhotos } from '../hooks/useSharedAlbumPhotos';

const noop = () => {};

// Handles both /share/:userMd5/:name and /share/:userMd5/:name/p/:photoId — the guest-facing
// counterpart to the library/album view, but deliberately self-contained: it never touches
// isAuthenticated/useAuth (that's for real accounts) or the /albums/:albumId route (which only
// renders inside the authenticated app shell). A share guest authenticates into a
// backend-managed ROLE_SHARE session scoped to exactly one album (see ShareController /
// ShareAccessFilter) — everything here talks to the /api/share and /api/albums/share endpoints
// built for that guest session, not the regular authenticated photo endpoints.
export default function SharedAlbumView() {
  const { messages } = useLocale();
  const { userMd5, name, photoId } = useParams();
  const navigate = useNavigate();

  // loading | not-found | expired | needs-password | ready | error
  const [status, setStatus] = useState('loading');
  const [albumId, setAlbumId] = useState(null);
  const [shareId, setShareId] = useState(null);

  const [password, setPassword] = useState('');
  const [authError, setAuthError] = useState(null);
  const [loginLoading, setLoginLoading] = useState(false);

  const fetchStatus = useCallback(async () => {
    setStatus('loading');
    try {
      const res = await fetch(`/api/share/${userMd5}/${name}`);
      if (res.status === 404) { setStatus('not-found'); return; }
      if (res.status === 410) { setStatus('expired'); return; }
      if (!res.ok) { setStatus('error'); return; }

      const data = await res.json();
      if (data.protected) {
        setShareId(data.shareId);
        setStatus('needs-password');
      } else {
        setAlbumId(data.albumId);
        setStatus('ready');
      }
    } catch (err) {
      console.error('Error fetching share link status:', err);
      setStatus('error');
    }
  }, [userMd5, name]);

  useEffect(() => { fetchStatus(); }, [fetchStatus]);

  const handlePasswordSubmit = async (e) => {
    e.preventDefault();
    setLoginLoading(true);
    setAuthError(null);
    try {
      const res = await fetch(`/api/share/${shareId}/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password }),
        credentials: 'include',
      });

      if (res.status === 410) { setStatus('expired'); return; }
      if (!res.ok) {
        setAuthError(res.status === 401 ? messages["incorrectPassword"] : messages["authenticationFailed"](res.statusText));
        return;
      }

      const data = await res.json();
      setAlbumId(data.albumId);
      setStatus('ready');
    } catch (err) {
      console.error('Error during share link login:', err);
      setAuthError(messages["shareLoginUnexpectedError"]);
    } finally {
      setLoginLoading(false);
    }
  };

  const { photos, hasMore, setPage } = useSharedAlbumPhotos(status === 'ready' ? albumId : null);

  // PhotoGrid only renders day-indexed data now (see hooks/useDayIndexedPhotos.js) - this guest/
  // share flow deliberately keeps its own flat, page-number-paginated fetch (useSharedAlbumPhotos,
  // hitting the separate guest-scoped /api/albums/share endpoint, no authenticated user/session to
  // resolve a day-index from), so it adapts locally instead: bucket the flat photos by their
  // formatted day (groupDate, present on every Photo DTO) to reproduce the same grouped display,
  // with the trailing bucket's sentinel driving the existing setPage pagination.
  const dayIndex = useMemo(() => {
    const photosByDay = {};
    const order = [];
    photos.forEach(p => {
      const key = p.groupDate || 'photos';
      if (!photosByDay[key]) { photosByDay[key] = []; order.push(key); }
      photosByDay[key].push(p);
    });
    const lastKey = order[order.length - 1];
    const dayPageState = {};
    order.forEach(day => { dayPageState[day] = { isLast: day !== lastKey || !hasMore }; });
    return {
      days: order.map(day => ({ day, count: photosByDay[day].length })),
      loadingDays: false,
      photosByDay,
      dayPageState,
      loadDayPage: () => setPage(p => p + 1),
    };
  }, [photos, hasMore, setPage]);

  const handlePhotoOpen = (photo) => navigate(`/share/${userMd5}/${name}/p/${photo.id}`);
  const handleCloseLightbox = () => navigate(`/share/${userMd5}/${name}`);

  const currentIndex = photoId ? photos.findIndex(p => p.id === photoId) : -1;
  const hasPrev = currentIndex > 0;
  const hasNext = currentIndex >= 0 && currentIndex < photos.length - 1;
  const handlePrev = () => hasPrev && navigate(`/share/${userMd5}/${name}/p/${photos[currentIndex - 1].id}`);
  const handleNext = () => hasNext && navigate(`/share/${userMd5}/${name}/p/${photos[currentIndex + 1].id}`);

  if (status === 'loading') {
    return <div className="content-body" style={{ textAlign: 'center', paddingTop: '40px' }}>{messages["loadingShareLink"]}</div>;
  }

  if (status === 'not-found') {
    return <div className="content-body" style={{ textAlign: 'center', paddingTop: '40px', color: '#ff3b30' }}>{messages["errorPrefix"]}{messages["shareLinkNotFound"]}</div>;
  }

  if (status === 'expired') {
    return <div className="content-body" style={{ textAlign: 'center', paddingTop: '40px', color: '#ff3b30' }}>{messages["errorPrefix"]}{messages["shareLinkExpired"]}</div>;
  }

  if (status === 'error') {
    return <div className="content-body" style={{ textAlign: 'center', paddingTop: '40px', color: '#ff3b30' }}>{messages["errorPrefix"]}{messages["shareLinkUnexpectedError"]}</div>;
  }

  if (status === 'needs-password') {
    return (
      <div className="modal-overlay">
        <div className="modal-content">
          <h2><Lock size={24} /> {messages["protectedLink"](name)}</h2>
          <p>{messages["passwordProtectedMessage"]}</p>
          {authError && <div className="error-message">{authError}</div>}
          <form onSubmit={handlePasswordSubmit}>
            <input
              type="password"
              placeholder={messages["enterPasswordPlaceholder"]}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="modal-input"
              required
              disabled={loginLoading}
            />
            <div className="modal-actions">
              <button type="submit" className="btn btn-primary" disabled={loginLoading}>
                {loginLoading ? messages["unlockingButton"] : messages["unlockButton"]}
              </button>
            </div>
          </form>
        </div>
      </div>
    );
  }

  return (
    <>
      {photos.length > 0 ? (
        <PhotoGrid
          dayIndex={dayIndex}
          selectedIds={[]}
          setSelectedIds={noop}
          setSelectedPhoto={handlePhotoOpen}
          isLightboxOpen={!!photoId}
        />
      ) : (
        <div className="content-body" style={{ textAlign: 'center', padding: '50px', color: '#888' }}>{messages["sharedAlbumEmpty"]}</div>
      )}
      {photoId && (
        <SharedLightbox
          albumId={albumId}
          photoId={photoId}
          onClose={handleCloseLightbox}
          onPrev={hasPrev ? handlePrev : null}
          onNext={hasNext ? handleNext : null}
        />
      )}
    </>
  );
}
