import React, { useState, useEffect, useRef } from 'react';
import { X, Calendar, Info, Camera, MapPin, Zap, Clock, Trash2, RotateCcw, ChevronLeft, ChevronRight, Edit, Check, XCircle, Crop } from 'lucide-react';
import Cropper from 'react-cropper';
import 'cropperjs/dist/cropper.css';

export default function Lightbox({ 
  selectedPhoto, 
  onClose, 
  extMetadata, 
  pathname, 
  handleMoveToGarbage, 
  handleRestore, 
  handlePermanentDelete,
  photos,
  onNavigate,
  onUpdateDate,
  onEditPhoto
}) {
  const [isEditingDate, setIsEditingDate] = useState(false);
  const [newDate, setNewDate] = useState('');

  // Cropping State
  const [isCropping, setIsCropping] = useState(false);
  const cropperRef = useRef(null);

  useEffect(() => {
    if (selectedPhoto) {
      const date = new Date(selectedPhoto.fullDateTime);
      const year = date.getFullYear();
      const month = String(date.getMonth() + 1).padStart(2, '0');
      const day = String(date.getDate()).padStart(2, '0');
      const hours = String(date.getHours()).padStart(2, '0');
      const minutes = String(date.getMinutes()).padStart(2, '0');
      setNewDate(`${year}-${month}-${day}T${hours}:${minutes}`);

      setIsCropping(false);
    }
  }, [selectedPhoto]);

  if (!selectedPhoto) return null;

  const currentIndex = photos.findIndex(p => p.id === selectedPhoto.id);
  const hasPrevious = currentIndex > 0;
  const hasNext = currentIndex !== -1 && currentIndex < photos.length - 1;

  const handlePrevious = (e) => {
    if (e) e.stopPropagation();
    if (hasPrevious && !isCropping) {
      onNavigate(photos[currentIndex - 1]);
    }
  };

  const handleNext = (e) => {
    if (e) e.stopPropagation();
    if (hasNext && !isCropping) {
      onNavigate(photos[currentIndex + 1]);
    }
  };

  const handleSaveDate = () => {
    onUpdateDate(selectedPhoto.id, newDate);
    setIsEditingDate(false);
  };

  const handleSaveCrop = (saveAsNew) => {
    if (cropperRef.current && cropperRef.current.cropper) {
      const cropper = cropperRef.current.cropper;
      const cropData = cropper.getData(true); // Gets rounded data (x, y, width, height, rotate)

      onEditPhoto(selectedPhoto.id, {
        cropX: cropData.x,
        cropY: cropData.y,
        width: cropData.width,
        height: cropData.height,
        rotation: cropData.rotate,
        saveAsNew: saveAsNew
      });
      setIsCropping(false);
    }
  };

  const handleRotateLeft = () => {
    if (cropperRef.current && cropperRef.current.cropper) {
      cropperRef.current.cropper.rotate(-90);
    }
  };

  const handleRotateRight = () => {
    if (cropperRef.current && cropperRef.current.cropper) {
      cropperRef.current.cropper.rotate(90);
    }
  };

  useEffect(() => {
    const handleKeyDown = (e) => {
      if (isCropping) return; // Disable keyboard nav while cropping
      if (e.key === 'ArrowLeft') handlePrevious();
      else if (e.key === 'ArrowRight') handleNext();
      else if (e.key === 'Escape') onClose();
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [hasPrevious, hasNext, currentIndex, isCropping]);

  return (
    <div className="lightbox-overlay" onClick={isCropping ? undefined : onClose}>
      <div className="lightbox-container" onClick={e => e.stopPropagation()}>
        <div className="lightbox-content" style={{ position: 'relative' }}>
          {isCropping ? (
            <div style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, zIndex: 10, display: 'flex', flexDirection: 'column' }}>
              <div style={{ flex: 1, position: 'relative', overflow: 'hidden' }}>
                <Cropper
                  src={`/api/photos/${selectedPhoto.id}/original`}
                  style={{ height: '100%', width: '100%' }}
                  initialAspectRatio={NaN}
                  guides={true}
                  ref={cropperRef}
                  viewMode={1}
                  dragMode="crop"
                  background={false}
                  responsive={true}
                  autoCropArea={0.8}
                  checkOrientation={false} // Let the backend handle orientation logic to avoid mismatch
                />
              </div>
              <div style={{ position: 'absolute', bottom: '20px', left: '50%', transform: 'translateX(-50%)', display: 'flex', gap: '15px', alignItems: 'center', background: 'rgba(0,0,0,0.7)', padding: '15px 20px', borderRadius: '12px' }}>
                 <button className="btn" onClick={handleRotateLeft}>Rotate Left</button>
                 <button className="btn" onClick={handleRotateRight}>Rotate Right</button>
                 <div style={{ display: 'flex', gap: '8px', marginLeft: '20px' }}>
                   <button className="btn btn-primary" onClick={() => handleSaveCrop(false)}>Save</button>
                   <button className="btn btn-primary" onClick={() => handleSaveCrop(true)}>Save as New</button>
                   <button className="btn" onClick={() => setIsCropping(false)}>Cancel</button>
                 </div>
              </div>
            </div>
          ) : selectedPhoto.contentType?.startsWith('video') ? (
            <video src={`/api/photos/${selectedPhoto.id}/stream`} controls autoPlay />
          ) : (
            <img src={`/api/photos/${selectedPhoto.id}/original`} />
          )}
          
          {hasPrevious && !isCropping && (
            <button className="lightbox-nav-btn prev" onClick={handlePrevious}>
              <ChevronLeft size={32} />
            </button>
          )}

          {hasNext && !isCropping && (
            <button className="lightbox-nav-btn next" onClick={handleNext}>
              <ChevronRight size={32} />
            </button>
          )}

          {!isCropping && (pathname === '/garbage' ? (
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
          ))}
        </div>
        <div className="metadata-sidebar">
          <div className="sidebar-header"><h3>Information</h3><X size={20} onClick={onClose} style={{cursor: 'pointer', opacity: 0.6}} /></div>
          <div className="sidebar-scroll-content">
            {!selectedPhoto.contentType?.startsWith('video') && !isCropping && (
               <div style={{ marginBottom: '20px' }}>
                 <button className="btn" style={{ width: '100%', justifyContent: 'center' }} onClick={() => setIsCropping(true)}>
                   <Crop size={16} /> Crop & Rotate
                 </button>
               </div>
            )}
            <div className="metadata-group"><label>File Name</label><p>{selectedPhoto.title}</p></div>
            <div className="metadata-group">
              <label><Calendar size={14} /> Date Taken</label>
              {isEditingDate ? (
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <input 
                    type="datetime-local" 
                    value={newDate} 
                    onChange={(e) => setNewDate(e.target.value)} 
                    className="modal-input" 
                    style={{ padding: '6px', fontSize: '13px' }}
                  />
                  <button className="btn" onClick={handleSaveDate} style={{ padding: '6px' }}><Check size={16} /></button>
                  <button className="btn" onClick={() => setIsEditingDate(false)} style={{ padding: '6px' }}><XCircle size={16} /></button>
                </div>
              ) : (
                <p style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  {selectedPhoto.fullDateTime}
                  <Edit size={14} onClick={() => setIsEditingDate(true)} style={{ cursor: 'pointer', opacity: 0.6 }} />
                </p>
              )}
            </div>
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
