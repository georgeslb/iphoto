import React, { useEffect, useState } from 'react';
import { X } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx';

// A small, standard-sized preview popup — not a full-screen Lightbox takeover. No dark
// backdrop, no metadata sidebar, no prev/next navigation, no edit/delete actions: just
// the image/video at a contained size, so the page behind (where the user is uploading)
// stays fully visible.
export default function UploadPreviewModal({ photoId, onClose }) {
    const { messages } = useLocale();
    const [photo, setPhoto] = useState(null);

    useEffect(() => {
        let cancelled = false;
        setPhoto(null);
        fetch(`/api/photos/${photoId}`)
            .then(res => res.ok ? res.json() : null)
            .then(data => { if (!cancelled) setPhoto(data); })
            .catch(() => { if (!cancelled) setPhoto(null); });
        return () => { cancelled = true; };
    }, [photoId]);

    useEffect(() => {
        const handleKeyDown = (e) => {
            if (e.key === 'Escape') onClose();
        };
        window.addEventListener('keydown', handleKeyDown);
        return () => window.removeEventListener('keydown', handleKeyDown);
    }, [onClose]);

    const mediaCacheParam = photo?.fileHash ? `?v=${photo.fileHash}` : '';

    return (
        <div className="upload-preview-overlay" onClick={onClose}>
            <div className="upload-preview-popup" onClick={e => e.stopPropagation()}>
                <button className="lightbox-close-btn upload-preview-close-btn" onClick={onClose}><X size={18}/></button>
                {!photo ? (
                    <div className="upload-preview-loading">{messages["loadingAsset"]}</div>
                ) : photo.contentType?.startsWith('video') ? (
                    <video src={`/api/photos/${photo.id}/stream${mediaCacheParam}`} controls autoPlay/>
                ) : (
                    <img src={`/api/photos/${photo.id}/original${mediaCacheParam}`} alt={photo.title || ''}/>
                )}
            </div>
        </div>
    );
}
