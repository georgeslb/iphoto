import React from 'react';
import { Check, Circle } from 'lucide-react';

function EntityGridDisplay({ items, selectedIds, onToggleSelect, onOpen, renderCover, emptyMessage }) {
  return (
    <div className="content-body">
      <div className="album-grid">
        {items.map(item => {
          const isSelected = selectedIds.includes(item.id);
          return (
            <div
              key={item.id}
              className={`album-card ${isSelected ? 'selected' : ''}`}
              onClick={() => (selectedIds.length > 0 ? onToggleSelect(item.id) : onOpen(item))}
            >
              <div
                className="select-overlay"
                onClick={(e) => {
                  e.stopPropagation();
                  onToggleSelect(item.id);
                }}
              >
                <div className={`check-circle ${isSelected ? 'checked' : ''}`}>
                  {isSelected ? <Check size={14} /> : <Circle size={14} color="#ccc" />}
                </div>
              </div>

              {renderCover(item)}
              <div className="album-info">
                <h3>{item.name}</h3>
                <p>{item.photoCountLabel}</p>
              </div>
            </div>
          );
        })}
        {items.length === 0 && (
          <div style={{ textAlign: 'center', padding: '50px', color: '#888', gridColumn: '1/-1' }}>{emptyMessage}</div>
        )}
      </div>
    </div>
  );
}

export default EntityGridDisplay;
