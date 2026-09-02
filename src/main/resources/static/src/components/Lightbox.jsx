import React, {useCallback, useEffect, useRef, useState} from 'react';
import Cropper from 'react-cropper';
import 'cropperjs/dist/cropper.css';
import {
    Calendar,
    Camera,
    CheckCircle,
    ChevronLeft,
    ChevronRight,
    Clock,
    Edit,
    Home,
    Info,
    MapPin,
    RotateCcw,
    RotateCw,
    Trash2,
    Unlink2,
    X,
    XCircle,
    Zap,
    ZoomIn,
    ZoomOut,
    Image as ImageIcon,
    MinusCircle
} from 'lucide-react';

import { useLocale } from '../LocaleContext.jsx'; // Import useLocale

export default function Lightbox({
                                     selectedPhoto,
                                     onClose,
                                     extMetadata,
                                     pathname,
                                     handleMoveToGarbage,
                                     handleMoveToMain,
                                     handleRestore,
                                     handlePermanentDelete,
                                     photos,
                                     onNavigate,
                                     onUpdateDate,
                                     onEditPhoto,
                                     onShowAddToAlbumModal,
                                     onShowRemoveFromAlbumModal,
                                     photoSelection,
                                     refreshAndMaintainScroll
                                 }) {
    const { messages } = useLocale(); // Use the useLocale hook
    const [isEditingDate, setIsEditingDate] = useState(false);
    const [newDate, setNewDate] = useState('');
    const [isEditingImage, setIsEditingImage] = useState(false);
    const [isLoading, setIsLoading] = useState(false);
    const imageRef = useRef(null);

    // Ref to the cropperjs instance (react-cropper), used for the crop/rotate/zoom image editor
    const cropperRef = useRef(null);
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
    // Set while hovering one specific .face-item in the "Faces Detected" list, to narrow the
    // on-image box display down to just that face. null means "no single face hovered" - falls
    // back to showing every box (as long as showFaces, from the zone-level hover, is still true).
    const [hoveredFaceId, setHoveredFaceId] = useState(null);
    const [imageSize, setImageSize] = useState({ naturalWidth: 0, naturalHeight: 0, displayWidth: 0, displayHeight: 0, offsetX: 0, offsetY: 0 });

    // State for editing which person a face maps to (see submitFacePerson below)
    const [editingFaceId, setEditingFaceId] = useState(null);
    const [editingFaceName, setEditingFaceName] = useState('');
    // Set when the backend reports the typed name already belongs to a different person —
    // { faceId, name, existingPerson } — drives the merge-confirmation popup.
    const [mergePrompt, setMergePrompt] = useState(null);

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
            setImageSize({ naturalWidth: 0, naturalHeight: 0, displayWidth: 0, displayHeight: 0, offsetX: 0, offsetY: 0 });

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

    const handleRemoveFace = useCallback(async (faceId) => {
        try {
            const response = await fetch(`/api/faces/${faceId}`, { method: 'DELETE' });
            if (!response.ok) {
                throw new Error(messages["errorRemovingFace"]);
            }
            // Drives both the thumbnail list and the on-image .face-box overlays, so removing
            // here updates both in one place.
            setFaces(prev => prev.filter(f => f.id !== faceId));
            // A deleted face can change which photos a person-scoped view (e.g. PersonDetail's
            // photo grid) should show — refresh whatever list is currently backing this Lightbox.
            if (refreshAndMaintainScroll) await refreshAndMaintainScroll();
        } catch (error) {
            console.error(messages["errorRemovingFace"], error);
        }
    }, [messages, refreshAndMaintainScroll]);

    // Sets/renames which person a face maps to. On a name collision with a DIFFERENT person the
    // backend returns 409 with that person's info — surfaced here as mergePrompt so the caller
    // (submitted again with confirmMerge: true) can decide whether to actually merge this one
    // face into them, rather than assuming.
    const submitFacePerson = useCallback(async (faceId, name, confirmMerge) => {
        try {
            const response = await fetch(`/api/faces/${faceId}/person`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name, confirmMerge }),
            });

            if (response.status === 409) {
                const existingPerson = await response.json();
                setMergePrompt({ faceId, name, existingPerson });
                return;
            }
            if (!response.ok) {
                throw new Error(messages["errorSettingFacePerson"]);
            }

            const updatedFace = await response.json();
            setFaces(prev => prev.map(f => (f.id === faceId ? { ...f, personName: updatedFace.personName } : f)));
            setEditingFaceId(null);
            setMergePrompt(null);
            // Renaming/reassigning a face can change which photos a person-scoped view (e.g.
            // PersonDetail) should show for the OLD and/or NEW person — refresh in place so
            // navigating back there reflects it, instead of showing a stale list.
            if (refreshAndMaintainScroll) await refreshAndMaintainScroll();
        } catch (error) {
            console.error(messages["errorSettingFacePerson"], error);
        }
    }, [messages, refreshAndMaintainScroll]);

    const handleFaceNameSubmit = useCallback((face) => {
        const trimmed = editingFaceName.trim();
        if (!trimmed || trimmed === (face.personName || '')) {
            setEditingFaceId(null);
            return;
        }
        submitFacePerson(face.id, trimmed, false);
    }, [editingFaceName, submitFacePerson]);

    const handleUnlinkFacePerson = useCallback(async (faceId) => {
        try {
            const response = await fetch(`/api/faces/${faceId}/person`, { method: 'DELETE' });
            if (!response.ok) {
                throw new Error(messages["errorUnlinkingFace"]);
            }
            setFaces(prev => prev.map(f => (f.id === faceId ? { ...f, personName: null } : f)));
            // Same reasoning as submitFacePerson above: this photo may need to drop out of the
            // person's photo list.
            if (refreshAndMaintainScroll) await refreshAndMaintainScroll();
        } catch (error) {
            console.error(messages["errorUnlinkingFace"], error);
        }
    }, [messages, refreshAndMaintainScroll]);


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
            // Reset zoom and pan when photo changes
            setZoomScale(1);
            setPanX(0);
            setPanY(0);
        }
    }, [selectedPhoto]);

    const currentIndex = selectedPhoto ? photos.findIndex(p => p.id === selectedPhoto.id) : -1;
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

    const handleCancelEditing = () => {
        // The <Cropper> unmounts when isEditingImage flips false, so cropperjs's
        // internal crop box/rotation/zoom state is discarded with it — nothing to reset here.
        setIsEditingImage(false);
    };

    const handleSaveEditedImage = async (saveAsNew) => {
        const cropper = cropperRef.current?.cropper;
        if (!cropper) return;
        setIsLoading(true);
        try {
            // getCroppedCanvas() renders at the crop box's actual (full-resolution) pixel
            // size by default — it reads from the original image data, not the on-screen
            // preview — so this keeps full quality regardless of zoom/rotation applied.
            const canvas = cropper.getCroppedCanvas({ imageSmoothingEnabled: true, imageSmoothingQuality: 'high' });
            const blob = canvas ? await new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', 1)) : null;
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
    };

    const recalculateImageSize = useCallback(() => {
        const img = imageRef.current;
        const container = zoomContainerRef.current;
        if (!img || !container || !img.naturalWidth) return;

        const { naturalWidth, naturalHeight } = img;
        const containerWidth = container.clientWidth;
        const containerHeight = container.clientHeight;

        // The <img> is centered inside zoom-container via object-fit: contain, so whenever its
        // aspect ratio doesn't exactly match the container's, there's a letterbox gap on two
        // sides — face-box overlays need the actual rendered rect (size + centering offset), not
        // just the container's own box, or they end up positioned off the real image. Same
        // rendered-size math clampPan uses below for pan-clamping.
        let displayWidth = naturalWidth;
        let displayHeight = naturalHeight;
        const containerRatio = containerWidth / containerHeight;
        const imageRatio = naturalWidth / naturalHeight;
        if (imageRatio > containerRatio) {
            displayWidth = containerWidth;
            displayHeight = containerWidth / imageRatio;
        } else {
            displayHeight = containerHeight;
            displayWidth = containerHeight * imageRatio;
        }

        setImageSize({
            naturalWidth,
            naturalHeight,
            displayWidth,
            displayHeight,
            offsetX: (containerWidth - displayWidth) / 2,
            offsetY: (containerHeight - displayHeight) / 2,
        });
    }, []);

    const handleImageLoad = recalculateImageSize;

    // The face-box overlays are positioned from imageSize, computed once when the image loads —
    // but zoom-container's own size (and so the image's rendered/letterboxed rect within it) can
    // change afterward, e.g. the browser window being resized while a photo is open. Without
    // this, imageSize goes stale and every face-box drifts from the actual image.
    useEffect(() => {
        window.addEventListener('resize', recalculateImageSize);
        return () => window.removeEventListener('resize', recalculateImageSize);
    }, [recalculateImageSize]);

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

    // React attaches its synthetic wheel listener as passive (for scroll performance), so
    // calling preventDefault() from a JSX onWheel handler throws "Unable to preventDefault
    // inside passive event listener invocation" and never actually stops page scroll. Attach
    // a real, non-passive listener via the DOM instead.
    useEffect(() => {
        const container = zoomContainerRef.current;
        if (!container) return;
        container.addEventListener('wheel', handleWheel, { passive: false });
        return () => container.removeEventListener('wheel', handleWheel);
    }, [handleWheel]);

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
            if (e.key === 'Escape' && mergePrompt) { setMergePrompt(null); return; }
            if (e.key === 'ArrowLeft') handlePrevious();
            else if (e.key === 'ArrowRight') handleNext();
            else if (e.key === 'Escape') onClose();
        };
        window.addEventListener('keydown', handleKeyDown);
        return () => window.removeEventListener('keydown', handleKeyDown);
    }, [hasPrevious, hasNext, currentIndex, isEditingImage, onClose, mergePrompt]);

    const startImageEditing = useCallback(() => setIsEditingImage(true), []);

    if (!selectedPhoto) return null;

    // The id stays the same when a photo is edited in place ("Save"), but the file
    // changes, so bust the browser's URL-keyed cache with the (fresh) content hash.
    const mediaCacheParam = selectedPhoto.fileHash ? `?v=${selectedPhoto.fileHash}` : '';

    return (
        <>
        <div className="lightbox-overlay" onClick={isEditingImage ? undefined : onClose}>
            <style>{`
                .face-box { position: absolute; border: 2px solid rgba(255, 255, 0, 0.8); box-shadow: 0 0 10px rgba(0,0,0,0.5); border-radius: 4px; pointer-events: none; }
                .face-thumbnail-container { display: flex; flex-wrap: wrap; gap: 16px; margin-top: 8px; }
                .face-item { position: relative; display: flex; flex-direction: column; align-items: center; gap: 4px; }
                .face-thumbnail { width: 60px; height: 60px; border-radius: 4px; object-fit: cover; }
                .face-item label { font-size: 12px; color: #ccc; cursor: pointer; }
                .face-item label:hover { color: white; text-decoration: underline; }
                .face-name-input { width: 70px; font-size: 12px; padding: 2px 4px; border-radius: 4px; border: 1px solid #555; background: #222; color: white; text-align: center; }
                .face-remove-btn { position: absolute; top: -6px; right: -6px; width: 20px; height: 20px; display: flex; align-items: center; justify-content: center; border: none; border-radius: 50%; background: rgba(0,0,0,0.7); color: white; cursor: pointer; }
                .face-remove-btn:hover { background: #ff3b30; }
                .face-unlink-btn { position: absolute; top: -6px; left: -6px; width: 20px; height: 20px; display: flex; align-items: center; justify-content: center; border: none; border-radius: 50%; background: rgba(0,0,0,0.7); color: white; cursor: pointer; }
                .face-unlink-btn:hover { background: #ff9500; }
                .merge-prompt-faces { display: flex; align-items: center; justify-content: center; gap: 16px; margin: 16px 0; }
                .merge-prompt-faces img { width: 80px; height: 80px; border-radius: 8px; object-fit: cover; }
                .merge-prompt-faces span { font-size: 24px; color: var(--text-secondary); }
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
                            <div style={{ position: 'relative', flex: 1, minHeight: 0, overflow: 'hidden', background: '#000' }}>
                                <Cropper
                                    ref={cropperRef}
                                    src={`/api/photos/${selectedPhoto.id}/original${mediaCacheParam}`}
                                    style={{ height: '100%', width: '100%' }}
                                    viewMode={1}
                                    dragMode="crop"
                                    autoCropArea={1}
                                    background={false}
                                    checkOrientation={false}
                                    guides={true}
                                    responsive={true}
                                    zoomable={true}
                                    rotatable={true}
                                    cropBoxMovable={true}
                                    cropBoxResizable={true}
                                />
                            </div>
                            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '16px', padding: '12px 0', color: 'white', flexShrink: 0 }}>
                                <button className="btn" title={messages["rotateLeft"]} onClick={() => cropperRef.current?.cropper.rotate(-90)}><RotateCcw size={18}/></button>
                                <button className="btn" title={messages["rotateRight"]} onClick={() => cropperRef.current?.cropper.rotate(90)}><RotateCw size={18}/></button>
                                <button className="btn" title={messages["zoomOut"]} onClick={() => cropperRef.current?.cropper.zoom(-0.1)}><ZoomOut size={18}/></button>
                                <button className="btn" title={messages["zoomIn"]} onClick={() => cropperRef.current?.cropper.zoom(0.1)}><ZoomIn size={18}/></button>
                            </div>
                        </div>
                    ) : selectedPhoto.contentType?.startsWith('video') ? (
                        <video src={`/api/photos/${selectedPhoto.id}/stream${mediaCacheParam}`} controls autoPlay/>
                    ) : (
                        <div
                            ref={zoomContainerRef}
                            className="zoom-container"
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
                                overflow: 'hidden', // Hide overflow during pan/zoom
                                position: 'relative' // Positioning context for .face-box overlays below
                            }}
                        >
                            <img ref={imageRef} src={`/api/photos/${selectedPhoto.id}/original${mediaCacheParam}`} onLoad={handleImageLoad} style={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }} />
                            {showFaces && faces.map((face, index) => (
                                (hoveredFaceId === null || (face.id ?? index) === hoveredFaceId) &&
                                <div key={index} className="face-box" style={{
                                    left: `${imageSize.offsetX + face.x * scaleX}px`,
                                    top: `${imageSize.offsetY + face.y * scaleY}px`,
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
                        {photoSelection.canBeModified && !isEditingImage && (
                            <button className="btn lightbox-action-btn primary" onClick={startImageEditing}><Edit size={16}/> {messages["editImage"]}</button>
                        )}
                        {isEditingImage && (
                            <div style={{display: 'flex', gap: '8px', justifyContent: 'center', marginBottom: '10px'}}>
                                <button className="btn btn-primary" onClick={() => handleSaveEditedImage(false)} disabled={isLoading}>{messages["save"]}</button>
                                <button className="btn btn-primary" onClick={() => handleSaveEditedImage(true)} disabled={isLoading}>{messages["saveAsNew"]}</button>
                                <button className="btn" onClick={handleCancelEditing} disabled={isLoading}>{messages["cancel"]}</button>
                            </div>
                        )}

                        {photoSelection.canBeMovedToAlbum && !isEditingImage && <button className="btn lightbox-action-btn" onClick={() => onShowAddToAlbumModal(selectedPhoto.id)}><ImageIcon size={16}/> {messages["addToAlbumLightbox"]}</button>}
                        {photoSelection.canBeRemovedFromAlbum && !isEditingImage && <button className="btn lightbox-action-btn" onClick={() => onShowRemoveFromAlbumModal(selectedPhoto.id)}><MinusCircle size={16}/> {messages["removeFromAlbumLightbox"]}</button>}

                        {!isEditingImage && (
                            <>
                                {photoSelection.canBeRestored && <button className="btn lightbox-action-btn success" onClick={() => { handleRestore([selectedPhoto.id]); onClose(); }}><RotateCcw size={18}/> {messages["restoreAsset"]}</button>}
                                {photoSelection.canBeMovedToMain && <button className="btn lightbox-action-btn success" onClick={() => { handleMoveToMain([selectedPhoto.id]); onClose(); }}><Home size={18}/> {messages["moveToMainLightbox"]}</button>}
                                {photoSelection.canBeDeleted && <button className="btn lightbox-action-btn" onClick={() => { handleMoveToGarbage([selectedPhoto.id]); onClose(); }}><Trash2 size={18}/> {messages["moveToGarbageLightbox"]}</button>}
                                {photoSelection.canBePermanentlyDeleted && <button className="btn lightbox-action-btn danger" onClick={() => { handlePermanentDelete([selectedPhoto.id]); onClose(); }}><Trash2 size={18}/> {messages["deletePermanentlyLightbox"]}</button>}
                            </>
                        )}

                        <div className="metadata-group"><label>{messages["fileName"]}</label><p>{selectedPhoto.title}</p></div>
                        <div className="metadata-group">
                            <label><Calendar size={14}/> {messages["dateTaken"]}</label>
                            {isEditingDate ? (
                                <div style={{display: 'flex', alignItems: 'center', gap: '8px'}}>
                                    <input type="datetime-local" value={newDate} onChange={(e) => setNewDate(e.target.value)} className="modal-input" style={{padding: '6px', fontSize: '13px'}}/>
                                    <button className="btn" title={messages["save"]} onClick={handleSaveDate} style={{padding: '6px'}}><CheckCircle size={16}/></button>
                                    <button className="btn" title={messages["cancel"]} onClick={() => setIsEditingDate(false)} style={{padding: '6px'}}><XCircle size={16}/></button>
                                </div>
                            ) : (
                                <p style={{display: 'flex', alignItems: 'center', gap: '8px'}}>
                                    {formatDateTime(selectedPhoto.fullDateTime)}
                                    {photoSelection.canBeModified && <Edit size={14} onClick={() => setIsEditingDate(true)} className="edit-icon"/>}
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
                                onMouseLeave={() => { setShowFaces(false); setHoveredFaceId(null); }}
                            >
                                <div className="meta-divider">{messages["facesDetected"]}</div>
                                <div className="face-thumbnail-container">
                                    {faces.map((face, index) => (
                                        <div
                                            key={face.id || index}
                                            className="face-item"
                                            onMouseEnter={() => setHoveredFaceId(face.id ?? index)}
                                            onMouseLeave={() => setHoveredFaceId(null)}
                                        >
                                            {face.image && <img src={face.image} alt={face.personName || messages["faceLabel"](index)} className="face-thumbnail" />}
                                            {editingFaceId === face.id ? (
                                                <input
                                                    type="text"
                                                    className="face-name-input"
                                                    value={editingFaceName}
                                                    onChange={(e) => setEditingFaceName(e.target.value)}
                                                    onBlur={() => handleFaceNameSubmit(face)}
                                                    onKeyDown={(e) => {
                                                        if (e.key === 'Enter') handleFaceNameSubmit(face);
                                                        else if (e.key === 'Escape') setEditingFaceId(null);
                                                    }}
                                                    autoFocus
                                                />
                                            ) : (
                                                <label
                                                    title={messages["editFaceName"]}
                                                    onClick={() => { setEditingFaceId(face.id); setEditingFaceName(face.personName || ''); }}
                                                >
                                                    {face.personName || messages["faceLabel"](index)}
                                                </label>
                                            )}
                                            {face.id && face.personName && (
                                                <button
                                                    type="button"
                                                    className="face-unlink-btn"
                                                    title={messages["unlinkFaceFromPerson"]}
                                                    onClick={() => handleUnlinkFacePerson(face.id)}
                                                >
                                                    <Unlink2 size={12}/>
                                                </button>
                                            )}
                                            {face.id && (
                                                <button
                                                    type="button"
                                                    className="face-remove-btn"
                                                    title={messages["removeFace"]}
                                                    onClick={() => handleRemoveFace(face.id)}
                                                >
                                                    <Trash2 size={12}/>
                                                </button>
                                            )}
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
        {mergePrompt && (
            <div className="modal-overlay" onClick={() => setMergePrompt(null)}>
                <div className="modal-content" onClick={(e) => e.stopPropagation()}>
                    <h2>{messages["mergeFaceIntoPersonTitle"]}</h2>
                    <p>{messages["mergeFaceIntoPersonPrompt"](mergePrompt.existingPerson.name)}</p>
                    <div className="merge-prompt-faces">
                        <img src={mergePrompt.existingPerson.coverPhoto?.image} alt={mergePrompt.existingPerson.name} />
                        <span>+</span>
                        <img src={faces.find(f => f.id === mergePrompt.faceId)?.image} alt={mergePrompt.name} />
                    </div>
                    <div className="modal-actions">
                        <button className="btn" onClick={() => setMergePrompt(null)}>{messages["cancel"]}</button>
                        <button
                            className="btn btn-primary"
                            onClick={() => submitFacePerson(mergePrompt.faceId, mergePrompt.name, true)}
                        >
                            {messages["confirmMergeButton"]}
                        </button>
                    </div>
                </div>
            </div>
        )}
        </>
    );
}