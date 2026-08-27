import React, { useState, useEffect } from 'react';
import { X, ChevronLeft, ChevronRight } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx';

// A deliberately minimal, read-only photo view for anonymous share guests — reached via
// /share/:userMd5/:name/p/:photoId. Unlike the main Lightbox.jsx (zoom/pan, date editing, face
// detection, delete...), a guest has no account and no write access to any of that, so none of
// it applies here; this just shows the photo full-screen with prev/next/close.
export default function SharedLightbox({ albumId, photoId, onClose, onPrev, onNext }) {
  const { messages } = useLocale();
  const [photo, setPhoto] = useState(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    setPhoto(null);
    setError(false);
    let cancelled = false;

    fetch(`/api/albums/share/${albumId}/photos/${photoId}`)
      .then(res => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
      })
      .then(data => { if (!cancelled) setPhoto(data); })
      .catch(() => { if (!cancelled) setError(true); });

    return () => { cancelled = true; };
  }, [albumId, photoId]);

  useEffect(() => {
    const handleKeyDown = (e) => {
      if (e.key === 'Escape') onClose();
      else if (e.key === 'ArrowLeft' && onPrev) onPrev();
      else if (e.key === 'ArrowRight' && onNext) onNext();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose, onPrev, onNext]);

  return (
    <div className="shared-lightbox-overlay" onClick={onClose}>
      <button type="button" className="shared-lightbox-close" onClick={onClose} title={messages["close"]}>
        <X size={28} />
      </button>
      {onPrev && (
        <button
          type="button"
          className="shared-lightbox-nav shared-lightbox-prev"
          onClick={(e) => { e.stopPropagation(); onPrev(); }}
        >
          <ChevronLeft size={32} />
        </button>
      )}
      {onNext && (
        <button
          type="button"
          className="shared-lightbox-nav shared-lightbox-next"
          onClick={(e) => { e.stopPropagation(); onNext(); }}
        >
          <ChevronRight size={32} />
        </button>
      )}
      <div className="shared-lightbox-content" onClick={(e) => e.stopPropagation()}>
        {error ? (
          <div className="shared-lightbox-message">{messages["shareLinkUnexpectedError"]}</div>
        ) : !photo ? (
          <div className="shared-lightbox-message">{messages["loadingAsset"]}</div>
        ) : photo.contentType?.startsWith('video') ? (
          <video src={`/api/photos/${photoId}/stream`} controls autoPlay className="shared-lightbox-media" />
        ) : (
          <img src={`/api/photos/${photoId}/original`} alt={photo.title || ''} className="shared-lightbox-media" />
        )}
      </div>
    </div>
  );
}
