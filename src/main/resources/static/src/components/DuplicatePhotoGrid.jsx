import React from 'react';
import { Check } from 'lucide-react';

function DuplicatePhotoGrid({
  currentDuplicateGroup,
  selectedIds,
  setSelectedIds,
  handlePhotoClick,
  duplicatesPage,
  fetchDuplicates,
  isLastDuplicateGroup,
  handleFinishComparison
}) {
  if (!currentDuplicateGroup) {
    return <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>No duplicates found. All clean!</div>;
  }

  return (
    <div className="content-body" style={{ textAlign: 'center', paddingTop: '40px' }}>
      <div className="duplicate-group">
        <h3 style={{ marginBottom: '30px' }}>Duplicate Group ({currentDuplicateGroup.photos.length} items)</h3>
        <div className="photo-grid" style={{ justifyContent: 'center' }}>
          {currentDuplicateGroup.photos.map(p => {
            const aspectRatio = (p.width && p.height) ? (p.width / p.height) : 1;
            const isSelected = selectedIds.includes(p.id);
            return (
              <div
                key={p.id}
                className={`photo-card ${isSelected ? 'selected' : ''}`}
                style={{ flexBasis: `${aspectRatio * 400}px`, height: '400px', flexGrow: 0, maxWidth: '100%' }}
                onClick={() => {
                  if (selectedIds.length > 0) {
                    setSelectedIds(v => v.includes(p.id) ? v.filter(i => i !== p.id) : [...v, p.id]);
                  } else {
                    handlePhotoClick(p);
                  }
                }}
              >
                <div
                  className="select-overlay"
                  onClick={(e) => {
                    e.stopPropagation();
                    setSelectedIds(v => v.includes(p.id) ? v.filter(i => i !== p.id) : [...v, p.id]);
                  }}
                >
                  <div className={`check-circle ${isSelected ? 'checked' : ''}`}>
                    {isSelected && <Check size={14} />}
                  </div>
                </div>
                <img src={p.url} loading="lazy" style={{ width: '100%', height: '100%', objectFit: 'contain', background: '#e0e0e5' }} alt="Duplicate" />
              </div>
            );
          })}
        </div>
        <div style={{ display: 'flex', justifyContent: 'center', gap: '20px', marginTop: '40px' }}>
          <button className="btn" onClick={() => fetchDuplicates(duplicatesPage - 1)} disabled={duplicatesPage === 0}>Previous Group</button>
          <button className="btn" style={{color: '#007aff', borderColor: '#007aff'}} onClick={handleFinishComparison}>Finish Comparison</button>
          <button className="btn btn-primary" onClick={() => fetchDuplicates(duplicatesPage + 1)} disabled={isLastDuplicateGroup}>Next Group</button>
        </div>
      </div>
    </div>
  );
}

export default DuplicatePhotoGrid;