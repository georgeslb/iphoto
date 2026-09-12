import React from 'react';
import { Unlink2 } from 'lucide-react';
import { useLocale } from '../LocaleContext.jsx';

// Grid of one person's distinct face crops (PersonDetail.jsx's "Faces" tab), newest-first -
// sibling of the "Photos" tab's day-indexed PhotoGrid, but listing the actual detected face crops
// instead of the photos they came from. Each card can be unlinked in place - same action/icon as
// Lightbox.jsx's own per-photo "Faces Detected" list (DELETE /api/faces/{id}/person), just reached
// from the person's own page instead of one specific photo.
export default function PersonFacesGrid({ faces, loading, hasMore, onLoadMore, onUnlink }) {
  const { messages } = useLocale();

  if (!loading && faces.length === 0) {
    return <div style={{ textAlign: 'center', padding: '50px', color: 'var(--text-secondary)' }}>{messages["noFacesForPerson"]}</div>;
  }

  return (
    <div>
      <div className="person-faces-grid">
        {faces.map(face => (
          <div key={face.id} className="person-face-card">
            {face.image && <img src={face.image} alt="" />}
            <button
              type="button"
              className="person-face-unlink-btn"
              title={messages["unlinkFaceFromPerson"]}
              onClick={() => onUnlink(face.id)}
            >
              <Unlink2 size={14} />
            </button>
          </div>
        ))}
      </div>
      {loading && (
        <div style={{ textAlign: 'center', padding: '20px', color: 'var(--text-secondary)' }}>{messages["loadingFaces"]}</div>
      )}
      {!loading && hasMore && (
        <div style={{ textAlign: 'center', padding: '20px' }}>
          <button type="button" className="btn" onClick={onLoadMore}>{messages["loadMoreFacesButton"]}</button>
        </div>
      )}
    </div>
  );
}
