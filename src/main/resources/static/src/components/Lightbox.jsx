import React, {useCallback, useEffect, useRef, useState} from 'react';
import {
    Calendar,
    Camera,
    ChevronLeft,
    ChevronRight,
    Clock,
    Edit,
    Info,
    MapPin,
    RotateCcw,
    Trash2,
    X,
    XCircle,
    Zap,
    Image as ImageIcon,
    MinusCircle,
    Users
} from 'lucide-react';

import 'tui-image-editor/dist/tui-image-editor.css';
import 'tui-color-picker/dist/tui-color-picker.css';
import ImageEditor from 'tui-image-editor';

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
                                     onEditPhoto,
                                     onShowAddToAlbumModal,
                                     onShowRemoveFromAlbumModal
                                 }) {
    const [isEditingDate, setIsEditingDate] = useState(false);
    const [newDate, setNewDate] = useState('');
    const [isEditingImage, setIsEditingImage] = useState(false);
    const [isLoading, setIsLoading] = useState(false);
    const [isApplyButtonVisible, setIsApplyButtonVisible] = useState(false);
    const imageEditorRef = useRef(null);
    const editorInstance = useRef(null);
    const imageRef = useRef(null);

    // State for face detection
    const [faces, setFaces] = useState([]);
    const [showFaces, setShowFaces] = useState(false);
    const [imageSize, setImageSize] = useState({ naturalWidth: 0, naturalHeight: 0, displayWidth: 0, displayHeight: 0 });


    const formatDateTime = (dateTimeString) => {
        if (!dateTimeString) return '';
        try {
            const date = new Date(dateTimeString);
            if (isNaN(date.getTime())) {
                const parts = dateTimeString.split(/[-T :]/).map(Number);
                if (parts.length >= 6) {
                    date.setFullYear(parts[0], parts[1] - 1, parts[2]);
                    date.setHours(parts[3], parts[4], parts[5]);
                } else {
                    return dateTimeString;
                }
            }
            const day = String(date.getDate()).padStart(2, '0');
            const month = String(date.getMonth() + 1).padStart(2, '0');
            const year = date.getFullYear();
            const hours = String(date.getHours()).padStart(2, '0');
            const minutes = String(date.getMinutes()).padStart(2, '0');
            const seconds = String(date.getSeconds()).padStart(2, '0');
            return `${day}-${month}-${year} ${hours}:${minutes}:${seconds}`;
        } catch (e) {
            console.error("Error formatting date:", e);
            return dateTimeString;
        }
    };

    // Fetch faces when a new photo is selected
    useEffect(() => {
        if (selectedPhoto && selectedPhoto.id) {
            setFaces([]);
            setShowFaces(false);
            setImageSize({ naturalWidth: 0, naturalHeight: 0, displayWidth: 0, displayHeight: 0 });

            fetch(`/api/faces/photo/${selectedPhoto.id}`)
                .then(response => response.ok ? response.json() : [])
                .then(data => setFaces(data))
                .catch(error => console.error('Error fetching faces:', error));
        }
    }, [selectedPhoto]);


    useEffect(() => {
        if (selectedPhoto) {
            let formattedDate = '';
            if (selectedPhoto.fullDateTime) {
                const date = new Date(selectedPhoto.fullDateTime);
                if (!isNaN(date.getTime())) {
                    const year = date.getFullYear();
                    const month = String(date.getMonth() + 1).padStart(2, '0');
                    const day = String(date.getDate()).padStart(2, '0');
                    const hours = String(date.getHours()).padStart(2, '0');
                    const minutes = String(date.getMinutes()).padStart(2, '0');
                    formattedDate = `${year}-${month}-${day}T${hours}:${minutes}`;
                }
            }
            setNewDate(formattedDate);
            setIsEditingImage(false);
            if (editorInstance.current) {
                editorInstance.current.destroy();
                editorInstance.current = null;
            }
        }
    }, [selectedPhoto]);

    if (!selectedPhoto) return null;

    const currentIndex = photos.findIndex(p => p.id === selectedPhoto.id);
    const hasPrevious = currentIndex > 0;
    const hasNext = currentIndex !== -1 && currentIndex < photos.length - 1;

    const handlePrevious = (e) => {
        if (e) e.stopPropagation();
        if (hasPrevious && !isEditingImage) onNavigate(photos[currentIndex - 1]);
    };

    const handleNext = (e) => {
        if (e) e.stopPropagation();
        if (hasNext && !isEditingImage) onNavigate(photos[currentIndex + 1]);
    };

    const handleSaveDate = () => {
        onUpdateDate(selectedPhoto.id, newDate);
        setIsEditingDate(false);
    };

    const handleSaveEditedImage = async (saveAsNew) => {
        if (editorInstance.current) {
            setIsLoading(true);
            try {
                const dataURL = editorInstance.current.toDataURL({ format: 'jpeg', quality: 1 });
                const response = await fetch(dataURL);
                if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
                const blob = await response.blob();
                if (blob && blob.size > 0) {
                    const formData = new FormData();
                    formData.append('file', blob, selectedPhoto.title);
                    formData.append('saveAsNew', saveAsNew);
                    await onEditPhoto(selectedPhoto.id, formData);
                    setIsEditingImage(false);
                } else {
                    console.error('Blob is empty or invalid.');
                }
            } catch (error) {
                console.error('Error saving edited image:', error);
            } finally {
                setIsLoading(false);
            }
        }
    };

    const handleImageLoad = () => {
        if (imageRef.current) {
            const { naturalWidth, naturalHeight, clientWidth, clientHeight } = imageRef.current;
            setImageSize({ naturalWidth, naturalHeight, displayWidth: clientWidth, displayHeight: clientHeight });
        }
    };

    const scaleX = imageSize.naturalWidth > 0 ? imageSize.displayWidth / imageSize.naturalWidth : 0;
    const scaleY = imageSize.naturalHeight > 0 ? imageSize.displayHeight / imageSize.naturalHeight : 0;


    useEffect(() => {
        const handleKeyDown = (e) => {
            if (isEditingImage) return;
            if (e.key === 'ArrowLeft') handlePrevious();
            else if (e.key === 'ArrowRight') handleNext();
            else if (e.key === 'Escape') onClose();
        };
        window.addEventListener('keydown', handleKeyDown);
        return () => window.removeEventListener('keydown', handleKeyDown);
    }, [hasPrevious, hasNext, currentIndex, isEditingImage, onClose]);

    const startImageEditing = useCallback(() => setIsEditingImage(true), []);

    useEffect(() => {
        if (isEditingImage && imageEditorRef.current && selectedPhoto) {
            editorInstance.current = new ImageEditor(imageEditorRef.current, {
                includeUI: {
                    loadImage: { path: `/api/photos/${selectedPhoto.id}/original`, name: selectedPhoto.title },
                    menu: ['crop', 'rotate', 'flip', 'draw', 'shape', 'icon', 'text', 'filter'],
                    initMenu: 'crop',
                    menuBarPosition: 'bottom'
                },
                cssMaxWidth: window.innerWidth * 0.8,
                cssMaxHeight: window.innerHeight * 0.8,
                selectionStyle: { cornerSize: 20, rotatingPointOffset: 70 },
                usageStatistics: false,
            });

            const observer = new MutationObserver(() => {
                const applyButton = imageEditorRef.current?.querySelector('.tui-image-editor-menu-crop .tui-image-editor-button.apply');
                setIsApplyButtonVisible(!!(applyButton && applyButton.offsetParent !== null));
            });
            observer.observe(imageEditorRef.current, { childList: true, subtree: true, attributes: true });

            return () => {
                observer.disconnect();
                if (editorInstance.current) {
                    editorInstance.current.destroy();
                    editorInstance.current = null;
                }
            };
        }
    }, [isEditingImage, selectedPhoto]);

    return (
        <div className="lightbox-overlay" onClick={isEditingImage ? undefined : onClose}>
            <style>{`
                .tui-image-editor-header-buttons .tui-image-editor-load-btn { display: none !important; }
                .btn:disabled { background-color: #6c757d !important; color: #adb5bd !important; cursor: not-allowed !important; opacity: 0.6; }
                .btn-primary:disabled { background-color: #0056b3 !important; opacity: 0.6; }
                .face-box { position: absolute; border: 2px solid rgba(255, 255, 0, 0.8); box-shadow: 0 0 10px rgba(0,0,0,0.5); border-radius: 4px; pointer-events: none; }
                .face-thumbnail-container { display: flex; flex-wrap: wrap; gap: 16px; margin-top: 8px; }
                .face-item { display: flex; flex-direction: column; align-items: center; gap: 4px; }
                .face-thumbnail { width: 60px; height: 60px; border-radius: 4px; object-fit: cover; }
                .face-item label { font-size: 12px; color: #ccc; }
            `}</style>
            <div className="lightbox-container" onClick={e => e.stopPropagation()}>
                <div className="lightbox-content" style={{position: 'relative'}}>
                    {isLoading && (
                        <div style={{ position: 'absolute', inset: 0, backgroundColor: 'rgba(0, 0, 0, 0.7)', display: 'flex', justifyContent: 'center', alignItems: 'center', color: 'white', fontSize: '24px', zIndex: 100 }}>
                            Saving...
                        </div>
                    )}
                    {isEditingImage ? (
                        <div style={{ position: 'absolute', inset: '40px', zIndex: 10, display: 'flex', flexDirection: 'column', width: 'calc(100% - 80px)', height: 'calc(100% - 80px)' }}>
                            <div ref={imageEditorRef} style={{width: '100%', height: '100%'}}></div>
                        </div>
                    ) : selectedPhoto.contentType?.startsWith('video') ? (
                        <video src={`/api/photos/${selectedPhoto.id}/stream`} controls autoPlay/>
                    ) : (
                        <div style={{ position: 'relative', display: 'inline-block' }}>
                            <img ref={imageRef} src={`/api/photos/${selectedPhoto.id}/original`} onLoad={handleImageLoad} style={{ maxWidth: '100%', maxHeight: '100%' }} />
                            {showFaces && faces.map((face, index) => (
                                <div key={index} className="face-box" style={{
                                    left: `${face.x * scaleX}px`,
                                    top: `${face.y * scaleY}px`,
                                    width: `${face.width * scaleX}px`,
                                    height: `${face.height * scaleY}px`,
                                }}></div>
                            ))}
                        </div>
                    )}

                    {hasPrevious && !isEditingImage && <button className="lightbox-nav-btn prev" onClick={handlePrevious}><ChevronLeft size={32}/></button>}
                    {hasNext && !isEditingImage && <button className="lightbox-nav-btn next" onClick={handleNext}><ChevronRight size={32}/></button>}
                </div>
                <div className="metadata-sidebar">
                    <div className="sidebar-header">
                        <h3>Information</h3>
                        <button className="lightbox-close-btn" onClick={onClose}><X size={24}/></button>
                    </div>
                    <div className="sidebar-scroll-content">
                        {!selectedPhoto.contentType?.startsWith('video') && (
                            <div style={{marginBottom: '10px'}}>
                                {!isEditingImage ? (
                                    <button className="btn" style={{width: '100%', justifyContent: 'center'}} onClick={startImageEditing}><Edit size={16}/> Edit Image</button>
                                ) : (
                                    <div style={{display: 'flex', gap: '8px', justifyContent: 'center'}}>
                                        <button className="btn btn-primary" onClick={() => handleSaveEditedImage(false)} disabled={isLoading || isApplyButtonVisible}>Save</button>
                                        <button className="btn btn-primary" onClick={() => handleSaveEditedImage(true)} disabled={isLoading || isApplyButtonVisible}>Save as New</button>
                                        <button className="btn" onClick={() => setIsEditingImage(false)} disabled={isLoading}>Cancel</button>
                                    </div>
                                )}
                            </div>
                        )}

                        {!isEditingImage && <button className="btn" style={{width: '100%', justifyContent: 'center', marginBottom: '10px'}} onClick={() => onShowAddToAlbumModal(selectedPhoto.id)}><ImageIcon size={16}/> Add to Album</button>}
                        {!isEditingImage && <button className="btn" style={{width: '100%', justifyContent: 'center', marginBottom: '10px'}} onClick={() => onShowRemoveFromAlbumModal(selectedPhoto.id)}><MinusCircle size={16}/> Remove from Album</button>}

                        {!isEditingImage && (pathname === '/garbage' ? (
                            <div style={{display: 'flex', flexDirection: 'column', gap: '10px', marginBottom: '10px'}}>
                                <button className="btn" style={{ width: '100%', justifyContent: 'center', background: '#34c759', color: 'white', border: 'none' }} onClick={() => { handleRestore([selectedPhoto.id]); onClose(); }}><RotateCcw size={18}/> Restore Asset</button>
                                <button className="btn" style={{ width: '100%', justifyContent: 'center', background: '#ff3b30', color: 'white', border: 'none' }} onClick={() => { handlePermanentDelete([selectedPhoto.id]); onClose(); }}><Trash2 size={18}/> Delete Permanently</button>
                            </div>
                        ) : (
                            <button className="btn" style={{ width: '100%', justifyContent: 'center', background: 'rgba(255,255,255,0.2)', color: 'white', border: 'none', marginBottom: '10px' }} onClick={() => { handleMoveToGarbage([selectedPhoto.id]); onClose(); }}><Trash2 size={18}/> Move to Garbage</button>
                        ))}

                        <div className="metadata-group"><label>File Name</label><p>{selectedPhoto.title}</p></div>
                        <div className="metadata-group">
                            <label><Calendar size={14}/> Date Taken</label>
                            {isEditingDate ? (
                                <div style={{display: 'flex', alignItems: 'center', gap: '8px'}}>
                                    <input type="datetime-local" value={newDate} onChange={(e) => setNewDate(e.target.value)} className="modal-input" style={{padding: '6px', fontSize: '13px'}}/>
                                    <button className="btn" onClick={handleSaveDate} style={{padding: '6px'}}><XCircle size={16}/></button>
                                    <button className="btn" onClick={() => setIsEditingDate(false)} style={{padding: '6px'}}><XCircle size={16}/></button>
                                </div>
                            ) : (
                                <p style={{display: 'flex', alignItems: 'center', gap: '8px'}}>
                                    {formatDateTime(selectedPhoto.fullDateTime)}
                                    <Edit size={14} onClick={() => setIsEditingDate(true)} className="edit-icon"/>
                                </p>
                            )}
                        </div>
                        <div className="metadata-group"><label><Info size={14}/> Basic Details</label><p>{selectedPhoto.resolution} • {selectedPhoto.size}</p></div>
                        {extMetadata && (
                            <div className="extended-meta">
                                <div className="meta-divider">Technical</div>
                                {extMetadata.aperture && <div className="metadata-group"><label><Zap size={14}/> Exposure</label><p>{extMetadata.aperture} • {extMetadata.shutterSpeed} • ISO {extMetadata.iso}</p></div>}
                                {extMetadata.lensModel && <div className="metadata-group"><label><Camera size={14}/> Lens</label><p>{extMetadata.lensModel}</p></div>}
                                {extMetadata.codec && <div className="metadata-group"><label><Clock size={14}/> Video Info</label><p>{extMetadata.codec} • {extMetadata.frameRate} fps</p></div>}
                                {extMetadata.latitude && <div className="metadata-group"><label><MapPin size={14}/> Location</label><p>{extMetadata.latitude.toFixed(4)}, {extMetadata.longitude.toFixed(4)}</p></div>}
                            </div>
                        )}
                        {/* Faces Section */}
                        {faces.length > 0 && (
                            <div
                                className="extended-meta"
                                onMouseEnter={() => setShowFaces(true)}
                                onMouseLeave={() => setShowFaces(false)}
                            >
                                <div className="meta-divider">Faces Detected</div>
                                <div className="face-thumbnail-container">
                                    {faces.map((face, index) => (
                                        <div key={index} className="face-item">
                                            {face.image && <img src={face.image} alt={`Face ${index + 1}`} className="face-thumbnail" />}
                                            <label>Face {index + 1}</label>
                                        </div>
                                    ))}
                                </div>
                            </div>
                        )}
                        {selectedPhoto.fileSystemCreationDate && <div className="metadata-group"><label><Calendar size={14}/> File System Date</label><p>{formatDateTime(selectedPhoto.fileSystemCreationDate)}</p></div>}
                    </div>
                </div>
            </div>
        </div>
    );
}
