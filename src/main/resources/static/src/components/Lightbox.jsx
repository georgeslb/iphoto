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
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

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
    const { messages } = useLocale(); // Use the useLocale hook
    const [isEditingDate, setIsEditingDate] = useState(false);
    const [newDate, setNewDate] = useState('');
    const [isEditingImage, setIsEditingImage] = useState(false);
    const [isLoading, setIsLoading] = useState(false);
    const [isApplyButtonVisible, setIsApplyButtonVisible] = useState(false);
    const imageEditorRef = useRef(null);
    const editorInstance = useRef(null);
    const imageRef = useRef(null);
    const zoomContainerRef = useRef(null); // Ref for the zoomable container

    // State for zoom and pan
    const [zoomScale, setZoomScale] = useState(1);
    const [panX, setPanX] = useState(0);
    const [panY, setPanY] = useState(0);
    const [isDragging, setIsDragging] = useState(false);
    const [startX, setStartX] = useState(0);
    const [startY, setStartY] = useState(0);

    // State for face detection
    const [faces, setFaces] = useState([]);
    const [showFaces, setShowFaces] = useState(false);
    const [imageSize, setImageSize] = useState({ naturalWidth: 0, naturalHeight: 0, displayWidth: 0, displayHeight: 0 });

    // State for albumAllowed from SpaceDTO
    const [albumAllowed, setAlbumAllowed] = useState(false);


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
            console.error(messages["errorFormattingDate"], e);
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
                .catch(error => console.error(messages["errorFetchingFaces"], error));

            // Fetch SpaceDTO for albumAllowed property
            fetch(`/api/photos/${selectedPhoto.id}/space`)
                .then(response => response.ok ? response.json() : { albumAllowed: false })
                .then(data => setAlbumAllowed(data.albumAllowed))
                .catch(error => {
                    console.error(messages["errorFetchingSpaceInfo"], error);
                    setAlbumAllowed(false); // Default to false on error
                });
        }
    }, [selectedPhoto, messages]);


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
            // Reset zoom and pan when photo changes
            setZoomScale(1);
            setPanX(0);
            setPanY(0);
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
                if (!response.ok) throw new Error(messages["httpError"](response.status));
                const blob = await response.blob();
                if (blob && blob.size > 0) {
                    const formData = new FormData();
                    formData.append('file', blob, selectedPhoto.title);
                    formData.append('saveAsNew', saveAsNew);
                    await onEditPhoto(selectedPhoto.id, formData);
                    setIsEditingImage(false);
                } else {
                    console.error(messages["blobEmptyOrInvalid"]);
                }
            } catch (error) {
                console.error(messages["errorSavingEditedImage"], error);
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

    const clampPan = useCallback((newPanX, newPanY, currentScale) => {
        const img = imageRef.current;
        const container = zoomContainerRef.current;
        if (!img || !container) return { x: 0, y: 0 };

        // Get the actual rendered size of the image content within the container (before zoom transform)
        // This accounts for object-fit: contain
        const naturalImageWidth = img.naturalWidth;
        const naturalImageHeight = img.naturalHeight;
        const containerWidth = container.clientWidth;
        const containerHeight = container.clientHeight;

        let renderedImageWidth = naturalImageWidth;
        let renderedImageHeight = naturalImageHeight;

        const aspectRatioContainer = containerWidth / containerHeight;
        const aspectRatioImage = naturalImageWidth / naturalImageHeight;

        if (aspectRatioImage > aspectRatioContainer) {
            // Image is wider than container, height is constrained
            renderedImageWidth = containerWidth;
            renderedImageHeight = containerWidth / aspectRatioImage;
        } else {
            // Image is taller than container, width is constrained
            renderedImageHeight = containerHeight;
            renderedImageWidth = containerHeight * aspectRatioImage;
        }

        // Now, apply the current zoom scale to these rendered dimensions
        const scaledContentWidth = renderedImageWidth * currentScale;
        const scaledContentHeight = renderedImageHeight * currentScale;

        // Calculate the maximum allowed pan from the center
        // The pan values are offsets from the center of the container
        const maxPanRangeX = Math.max(0, (scaledContentWidth - containerWidth) / 2);
        const maxPanRangeY = Math.max(0, (scaledContentHeight - containerHeight) / 2);

        const clampedX = Math.max(-maxPanRangeX, Math.min(maxPanRangeX, newPanX));
        const clampedY = Math.max(-maxPanRangeY, Math.min(maxPanRangeY, newPanY));

        return { x: clampedX, y: clampedY };
    }, []); // Dependencies: imageRef and zoomContainerRef are refs, so they don't change

    // Zoom and Pan Handlers
    const handleWheel = useCallback((e) => {
        if (isEditingImage || selectedPhoto.contentType?.startsWith('video')) return;
        e.preventDefault(); // Prevent page scrolling

        const scaleAmount = 0.1;
        const newScale = e.deltaY < 0 ? zoomScale * (1 + scaleAmount) : zoomScale / (1 + scaleAmount);

        // Limit zoom scale
        const clampedScale = Math.max(1, Math.min(newScale, 5)); // Min 1x, Max 5x zoom

        if (clampedScale === zoomScale) {
            // If scale didn't change (e.g., already at max/min zoom), ensure pan is clamped
            const { x, y } = clampPan(panX, panY, zoomScale);
            if (x !== panX || y !== panY) {
                setPanX(x);
                setPanY(y);
            }
            return;
        }

        const container = zoomContainerRef.current;
        if (!container) return;

        const containerRect = container.getBoundingClientRect();

        // Mouse position relative to the container's top-left
        const mouseX = e.clientX - containerRect.left;
        const mouseY = e.clientY - containerRect.top;

        // Mouse position relative to the *center* of the container
        const mouseXRelativeToContainerCenter = mouseX - containerRect.width / 2;
        const mouseYRelativeToContainerCenter = mouseY - containerRect.height / 2;

        const ratio = clampedScale / zoomScale;

        // Calculate new pan values to keep the point under the mouse fixed relative to the image content
        // This formula is for transform-origin: center center
        const newPanX = mouseXRelativeToContainerCenter * (1 - ratio) + panX * ratio;
        const newPanY = mouseYRelativeToContainerCenter * (1 - ratio) + panY * ratio;

        // Apply clamping to the new pan values
        const { x: finalPanX, y: finalPanY } = clampPan(newPanX, newPanY, clampedScale);

        setPanX(finalPanX);
        setPanY(finalPanY);
        setZoomScale(clampedScale);
    }, [zoomScale, panX, panY, isEditingImage, selectedPhoto, clampPan]); // Added clampPan to dependencies

    const handleMouseDown = useCallback((e) => {
        if (isEditingImage || selectedPhoto.contentType?.startsWith('video') || zoomScale === 1) return;
        e.preventDefault();
        setIsDragging(true);
        setStartX(e.clientX);
        setStartY(e.clientY);
    }, [zoomScale, isEditingImage, selectedPhoto]);

    const handleMouseMove = useCallback((e) => {
        if (!isDragging || isEditingImage || selectedPhoto.contentType?.startsWith('video')) return;
        e.preventDefault();

        const dx = e.clientX - startX;
        const dy = e.clientY - startY;

        const newPanX = panX + dx;
        const newPanY = panY + dy;

        // Apply clamping to the new pan values
        const { x: finalPanX, y: finalPanY } = clampPan(newPanX, newPanY, zoomScale);

        setPanX(finalPanX);
        setPanY(finalPanY);

        // Update startX, startY for continuous dragging
        setStartX(e.clientX);
        setStartY(e.clientY);
    }, [isDragging, startX, startY, panX, panY, zoomScale, isEditingImage, selectedPhoto, clampPan]); // Added clampPan to dependencies


    const handleMouseUp = useCallback(() => {
        setIsDragging(false);
    }, []);

    const handleMouseLeave = useCallback(() => {
        setIsDragging(false); // Stop dragging if mouse leaves the container
    }, []);


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
                            {messages["saving"]}
                        </div>
                    )}
                    {isEditingImage ? (
                        <div style={{ position: 'absolute', inset: '40px', zIndex: 10, display: 'flex', flexDirection: 'column', width: 'calc(100% - 80px)', height: 'calc(100% - 80px)' }}>
                            <div ref={imageEditorRef} style={{width: '100%', height: '100%'}}></div>
                        </div>
                    ) : selectedPhoto.contentType?.startsWith('video') ? (
                        <video src={`/api/photos/${selectedPhoto.id}/stream`} controls autoPlay/>
                    ) : (
                        <div
                            ref={zoomContainerRef}
                            className="zoom-container"
                            onWheel={handleWheel}
                            onMouseDown={handleMouseDown}
                            onMouseMove={handleMouseMove}
                            onMouseUp={handleMouseUp}
                            onMouseLeave={handleMouseLeave}
                            style={{
                                transform: `scale(${zoomScale}) translate(${panX}px, ${panY}px)`,
                                cursor: zoomScale > 1 && isDragging ? 'grabbing' : (zoomScale > 1 ? 'grab' : 'default'),
                                transformOrigin: 'center center', // Changed to center center
                                willChange: 'transform',
                                display: 'flex', // To center the image within the container
                                justifyContent: 'center',
                                alignItems: 'center',
                                width: '100%', // Ensure container fills lightbox-content
                                height: '100%',
                                overflow: 'hidden' // Hide overflow during pan/zoom
                            }}
                        >
                            <img ref={imageRef} src={`/api/photos/${selectedPhoto.id}/original`} onLoad={handleImageLoad} style={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }} />
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
                        <h3>{messages["information"]}</h3>
                        <button className="lightbox-close-btn" onClick={onClose}><X size={24}/></button>
                    </div>
                    <div className="sidebar-scroll-content">
                        {!selectedPhoto.contentType?.startsWith('video') && (
                            <div style={{marginBottom: '10px'}}>
                                {!isEditingImage ? (
                                    <button className="btn" style={{width: '100%', justifyContent: 'center'}} onClick={startImageEditing}><Edit size={16}/> {messages["editImage"]}</button>
                                ) : (
                                    <div style={{display: 'flex', gap: '8px', justifyContent: 'center'}}>
                                        <button className="btn btn-primary" onClick={() => handleSaveEditedImage(false)} disabled={isLoading || isApplyButtonVisible}>{messages["save"]}</button>
                                        <button className="btn btn-primary" onClick={() => handleSaveEditedImage(true)} disabled={isLoading || isApplyButtonVisible}>{messages["saveAsNew"]}</button>
                                        <button className="btn" onClick={() => setIsEditingImage(false)} disabled={isLoading}>{messages["cancel"]}</button>
                                    </div>
                                )}
                            </div>
                        )}

                        {albumAllowed && !isEditingImage && <button className="btn" style={{width: '100%', justifyContent: 'center', marginBottom: '10px'}} onClick={() => onShowAddToAlbumModal(selectedPhoto.id)}><ImageIcon size={16}/> {messages["addToAlbumLightbox"]}</button>}
                        {albumAllowed && !isEditingImage && <button className="btn" style={{width: '100%', justifyContent: 'center', marginBottom: '10px'}} onClick={() => onShowRemoveFromAlbumModal(selectedPhoto.id)}><MinusCircle size={16}/> {messages["removeFromAlbumLightbox"]}</button>}

                        {!isEditingImage && (pathname === '/garbage' ? (
                            <div style={{display: 'flex', flexDirection: 'column', gap: '10px', marginBottom: '10px'}}>
                                <button className="btn" style={{ width: '100%', justifyContent: 'center', background: '#34c759', color: 'white', border: 'none' }} onClick={() => { handleRestore([selectedPhoto.id]); onClose(); }}><RotateCcw size={18}/> {messages["restoreAsset"]}</button>
                                <button className="btn" style={{ width: '100%', justifyContent: 'center', background: '#ff3b30', color: 'white', border: 'none' }} onClick={() => { handlePermanentDelete([selectedPhoto.id]); onClose(); }}><Trash2 size={18}/> {messages["deletePermanentlyLightbox"]}</button>
                            </div>
                        ) : (
                            <button className="btn" style={{ width: '100%', justifyContent: 'center', background: 'rgba(255,255,255,0.2)', color: 'white', border: 'none', marginBottom: '10px' }} onClick={() => { handleMoveToGarbage([selectedPhoto.id]); onClose(); }}><Trash2 size={18}/> {messages["moveToGarbageLightbox"]}</button>
                        ))}

                        <div className="metadata-group"><label>{messages["fileName"]}</label><p>{selectedPhoto.title}</p></div>
                        <div className="metadata-group">
                            <label><Calendar size={14}/> {messages["dateTaken"]}</label>
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
                        <div className="metadata-group"><label><Info size={14}/> {messages["basicDetails"]}</label><p>{selectedPhoto.resolution} • {selectedPhoto.size}</p></div>
                        {extMetadata && (
                            <div className="extended-meta">
                                <div className="meta-divider">{messages["technical"]}</div>
                                {extMetadata.aperture && <div className="metadata-group"><label><Zap size={14}/> {messages["exposure"]}</label><p>{extMetadata.aperture} • {extMetadata.shutterSpeed} • ISO {extMetadata.iso}</p></div>}
                                {extMetadata.lensModel && <div className="metadata-group"><label><Camera size={14}/> {messages["lens"]}</label><p>{extMetadata.lensModel}</p></div>}
                                {extMetadata.codec && <div className="metadata-group"><label><Clock size={14}/> {messages["videoInfo"]}</label><p>{extMetadata.codec} • {extMetadata.frameRate} fps</p></div>}
                                {extMetadata.latitude && <div className="metadata-group"><label><MapPin size={14}/> {messages["location"]}</label><p>{extMetadata.latitude.toFixed(4)}, {extMetadata.longitude.toFixed(4)}</p></div>}
                            </div>
                        )}
                        {/* Faces Section */}
                        {faces.length > 0 && (
                            <div
                                className="extended-meta"
                                onMouseEnter={() => setShowFaces(true)}
                                onMouseLeave={() => setShowFaces(false)}
                            >
                                <div className="meta-divider">{messages["facesDetected"]}</div>
                                <div className="face-thumbnail-container">
                                    {faces.map((face, index) => (
                                        <div key={index} className="face-item">
                                            {face.image && <img src={face.image} alt={messages["faceLabel"](index)} className="face-thumbnail" />}
                                            <label>{messages["faceLabel"](index)}</label>
                                        </div>
                                    ))}
                                </div>
                            </div>
                        )}
                        {selectedPhoto.fileSystemCreationDate && <div className="metadata-group"><label><Calendar size={14}/> {messages["fileSystemDate"]}</label><p>{formatDateTime(selectedPhoto.fileSystemCreationDate)}</p></div>}
                    </div>
                </div>
            </div>
        </div>
    );
}