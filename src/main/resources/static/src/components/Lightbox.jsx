import React from 'react';
import { X, Calendar, Info, Camera, MapPin, Zap, Clock, Trash2, RotateCcw } from 'lucide-react';

export default function Lightbox({ 
  selectedPhoto, 
  onClose, 
  extMetadata, 
  pathname, 
  handleMoveToGarbage, 
  handleRestore, 
  handlePermanentDelete 
}) {
  if (!selectedPhoto) return null;

  return (
    <div className="lightbox-overlay" onClick={onClose}>
      <div className="lightbox-container" onClick={e => e.stopPropagation()}>
        <div className="lightbox-content">
          {selectedPhoto.contentType?.startsWith('video') ? <video src={`/api/photos/${selectedPhoto.id}/stream`} controls autoPlay /> : <img src={`/api/photos/${selectedPhoto.id}/original`} />}
          {pathname === '/garbage' ? (
            <>
              <button className="btn" style={{position: 'absolute', bottom: '20px', left: '20px', background: '#34c759', color: 'white', border: 'none'}} onClick={() => { handleRestore([selectedPhoto.id]); onClose(); }}>
                <RotateCcw size={18}/> Restore Asset
              </button>
              <button className="btn" style={{position: 'absolute', bottom: '20px', left: '180px', background: '#ff3b30', color: 'white', border: 'none'}} onClick={() => { handlePermanentDelete([selectedPhoto.id]); onClose(); }}>
                <Trash2 size={18}/> Delete Permanently
              </button>
            </>
          ) : (
            <button className="btn" style={{position: 'absolute', bottom: '20px', left: '20px', background: 'rgba(255,255,255,0.2)', color: 'white', border: 'none'}} onClick={() => { handleMoveToGarbage([selectedPhoto.id]); onClose(); }}>
              <Trash2 size={18}/> Move to Garbage
            </button>
          )}
        </div>
        <div className="metadata-sidebar">
          <div className="sidebar-header"><h3>Information</h3><X size={20} onClick={onClose} style={{cursor: 'pointer', opacity: 0.6}} /></div>
          <div className="sidebar-scroll-content">
            <div className="metadata-group"><label>File Name</label><p>{selectedPhoto.title}</p></div>
            <div className="metadata-group"><label><Calendar size={14} /> Date Taken</label><p>{selectedPhoto.fullDateTime}</p></div>
            <div className="metadata-group"><label><Info size={14} /> Basic Details</label><p>{selectedPhoto.resolution} • {selectedPhoto.size}</p></div>
            {extMetadata && (
              <div className="extended-meta">
                <div className="meta-divider">Technical</div>
                {extMetadata.aperture && <div className="metadata-group"><label><Zap size={14}/> Exposure</label><p>{extMetadata.aperture} • {extMetadata.shutterSpeed} • ISO {extMetadata.iso}</p></div>}
                {extMetadata.lensModel && <div className="metadata-group"><label><Camera size={14}/> Lens</label><p>{extMetadata.lensModel}</p></div>}
                {extMetadata.codec && <div className="metadata-group"><label><Clock size={14}/> Video Info</label><p>{extMetadata.codec} • {extMetadata.frameRate} fps</p></div>}
                {extMetadata.latitude && <div className="metadata-group"><label><MapPin size={14}/> Location</label><p>{extMetadata.latitude.toFixed(4)}, {extMetadata.longitude.toFixed(4)}</p></div>}
              </div>
            )}
            {selectedPhoto.fileSystemCreationDate && (
              <div className="metadata-group">
                <label><Calendar size={14} /> File System Date</label>
                <p>{new Date(selectedPhoto.fileSystemCreationDate).toLocaleString()}</p>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
