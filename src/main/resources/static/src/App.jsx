import React, { useState, useEffect, useCallback, useRef } from 'react'
import { Routes, Route, useNavigate, useLocation, useParams } from 'react-router-dom'
import { MapContainer, TileLayer, Marker, Popup } from 'react-leaflet'
import { X, Image as ImageIcon, Copy, Trash2, BookOpen, Check } from 'lucide-react'
import 'leaflet/dist/leaflet.css'
import './App.css'

import L from 'leaflet'
import markerIcon from 'leaflet/dist/images/marker-icon.png'
import markerShadow from 'leaflet/dist/images/marker-shadow.png'
import Sidebar from './components/Sidebar'
import PhotoGrid from './components/PhotoGrid'
import Header from './components/Header'
import Lightbox from './components/Lightbox'
import ShareLinkPage from './components/ShareLinkPage'; // Import the new component
import ShareLoginPage from './components/ShareLoginPage'; // Import the new ShareLoginPage component

let DefaultIcon = L.icon({ iconUrl: markerIcon, shadowUrl: markerShadow, iconSize: [25, 41], iconAnchor: [12, 41] })
L.Marker.prototype.options.icon = DefaultIcon

const CHUNK_SIZE = 1024 * 1024 * 5; // 5MB chunks

function App() {
  const [photos, setPhotos] = useState([])
  
  // Duplicates State
  const [duplicatesPage, setDuplicatesPage] = useState(0);
  const [currentDuplicateGroup, setCurrentDuplicateGroup] = useState(null);
  const [isLastDuplicateGroup, setIsLastDuplicateGroup] = useState(false);

  const [albums, setAlbums] = useState([])
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(true)
  const [uploadQueue, setUploadQueue] = useState([])
  const [selectedPhoto, setSelectedPhoto] = useState(null)
  const [extMetadata, setExtMetadata] = useState(null)
  const [mapAssets, setMapAssets] = useState([])
  const [selectedIds, setSelectedIds] = useState([])
  const [showCreateAlbumModal, setShowCreateAlbumModal] = useState(false)
  const [showAddToAlbumModal, setShowAddToAlbumModal] = useState(false);
  const [targetAlbumIds, setTargetAlbumIds] = useState([]);
  const [newAlbumName, setNewAlbumName] = useState('');
  const [newAlbumDescription, setNewAlbumDescription] = useState('');
  const [albumCreationError, setAlbumCreationError] = useState(null);

  // Share Link State
  const [showCreateShareLinkModal, setShowCreateShareLinkModal] = useState(false);
  const [albumIdForShareLink, setAlbumIdForShareLink] = useState(null); // New state for album ID
  const [newShareLinkName, setNewShareLinkName] = useState('');
  const [newShareLinkPassword, setNewShareLinkPassword] = useState('');
  const [newShareLinkExpiresInDays, setNewShareLinkExpiresInDays] = useState(7);
  const [shareLinkCreationError, setShareLinkCreationError] = useState(null);


  const navigate = useNavigate();
  const location = useLocation();
  const mainContentRef = useRef(null);
  const prevSectionRef = useRef(null);

  // Background location to keep grid mounted when showing lightbox over it
  const backgroundLocation = location.state?.backgroundLocation;
  const effectivePath = backgroundLocation?.pathname || location.pathname;

  const fetchPhotos = useCallback(async (pageNum, reset = false, type = 'library', albumId = null) => {
    try {
      let endpoint = '/api/photos';
      let sort = 'dateTaken,desc';

      if (type === 'garbage') {
        endpoint = '/api/photos/garbage';
        sort = 'garbageAt,desc';
      } else if (type === 'album' && albumId) {
        endpoint = `/api/albums/${albumId}/photos`;
      }

      const res = await fetch(`${endpoint}?page=${pageNum}&size=50&sort=${sort}`)
      const data = await res.json()
      
      const content = Array.isArray(data) ? data : (data.content || []);
      const isLast = Array.isArray(data) ? true : (data.last === undefined ? true : data.last);

      setPhotos(prev => reset ? content : [...prev, ...content])
      setHasMore(!isLast)
    } catch (err) { console.error("Fetch photos failed:", err) }
  }, [])

  const fetchAlbums = useCallback(async () => {
    try {
      const res = await fetch('/api/albums');
      const data = await res.json();
      setAlbums(data);
    } catch (err) { console.error("Fetch albums failed:", err); }
  }, []);

  const fetchDuplicates = useCallback(async (pageNum) => {
    try {
      const res = await fetch(`/api/photos/duplicates?page=${pageNum}&size=1`);
      if (res.ok) {
        const data = await res.json();
        if (data.content && data.content.length > 0) {
          const group = data.content[0];
          setCurrentDuplicateGroup(group);
          setDuplicatesPage(pageNum);
          setIsLastDuplicateGroup(data.last === undefined ? true : data.last);
          window.history.replaceState(null, '', `/duplicates/${group.hash}?page=${pageNum}`);
        } else if (pageNum > 0) {
          // If this page is now empty (e.g. resolved a duplicate) but it wasn't page 0, fetch previous page
          fetchDuplicates(pageNum - 1);
        } else {
          setCurrentDuplicateGroup(null);
          setIsLastDuplicateGroup(true);
          setDuplicatesPage(0);
          window.history.replaceState(null, '', `/duplicates`);
        }
      }
    } catch(err) { console.error("Fetch duplicates failed", err); }
  }, []);

  const handleFinishComparison = async () => {
    if (!currentDuplicateGroup) return;
    try {
      const res = await fetch('/api/photos/duplicates/finishChanges', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ hash: currentDuplicateGroup.hash })
      });
      if (res.ok) {
        // The current group is closed, so we fetch the same page number to get the next open group
        await fetchDuplicates(duplicatesPage);
      } else {
        throw new Error('Failed to finish comparison');
      }
    } catch (err) {
      alert(`Error: ${err.message}`);
    }
  };

  const refreshAndMaintainScroll = useCallback(async () => {
    const scrollPosition = mainContentRef.current?.scrollTop;
    const currentPage = page;
    
    if (effectivePath.startsWith('/duplicates')) {
       await fetchDuplicates(duplicatesPage);
       return;
    }

    const type = effectivePath === '/garbage' ? 'garbage' : (effectivePath.startsWith('/albums/') ? 'album' : 'library');
    const albumId = type === 'album' ? effectivePath.split('/').pop() : null;

    const fetchPage = async (pageNum) => {
        let endpoint = '/api/photos';
        let sort = 'dateTaken,desc';

        if (type === 'garbage') {
            endpoint = '/api/photos/garbage';
            sort = 'garbageAt,desc';
        } else if (type === 'album' && albumId) {
            endpoint = `/api/albums/${albumId}/photos`;
        }
        
        const res = await fetch(`${endpoint}?page=${pageNum}&size=50&sort=${sort}`);
        if (!res.ok) return [];
        const data = await res.json();
        return Array.isArray(data) ? data : (data.content || []);
    };

    const allPagesPromises = [];
    for (let i = 0; i <= currentPage; i++) {
        allPagesPromises.push(fetchPage(i));
    }

    const allPagesContent = await Promise.all(allPagesPromises);
    const newPhotos = allPagesContent.flat();
    
    setPhotos(newPhotos);
    await fetchAlbums();

    if (scrollPosition && mainContentRef.current) {
        requestAnimationFrame(() => {
            if(mainContentRef.current) {
                mainContentRef.current.scrollTop = scrollPosition;
            }
        });
    }
  }, [page, effectivePath, fetchAlbums, duplicatesPage, fetchDuplicates]);

  useEffect(() => {
    // Determine effective path for fetching data
    const currentSection = effectivePath.split('/')[1] || 'library';
    const prevSection = prevSectionRef.current;
    prevSectionRef.current = currentSection;

    // Ignore direct navigation to an asset (handled by Lightbox and fallback logic)
    // By returning early here, we DO NOT fetch the grid photos if the user hard-refreshes the page on /asset/:id.
    // This perfectly solves the issue of the background grid requesting massive amounts of thumbnails unnecessarily!
    if (location.pathname.startsWith('/asset/')) return;

    // Prevent resetting state if we are just moving within the duplicates section (e.g. from /duplicates to /duplicates/hash)
    if (currentSection === 'duplicates' && prevSection === 'duplicates') {
      return;
    }

    setPhotos([]); setPage(0); setHasMore(true); setSelectedIds([]);
    
    const albumMatch = effectivePath.match(/^\/albums\/(.+)$/);

    if (effectivePath === '/') fetchPhotos(0, true, 'library');
    else if (effectivePath === '/garbage') fetchPhotos(0, true, 'garbage');
    else if (effectivePath === '/map') fetch('/api/photos/locations').then(r => r.json()).then(setMapAssets);
    else if (effectivePath.startsWith('/duplicates')) {
      // Read page number from URL if it exists (for hard refreshes)
      const urlParams = new URLSearchParams(window.location.search);
      const pageParam = parseInt(urlParams.get('page'), 10);
      const targetPage = !isNaN(pageParam) ? pageParam : 0;
      
      setDuplicatesPage(targetPage);
      fetchDuplicates(targetPage);
    }
    
    if (effectivePath.startsWith('/albums')) fetchAlbums();

    if (albumMatch) fetchPhotos(0, true, 'album', albumMatch[1]);
  }, [effectivePath, fetchPhotos, fetchAlbums, fetchDuplicates, location.pathname])

  useEffect(() => { 
    if (page > 0) {
      const albumMatch = effectivePath.match(/^\/albums\/(.+)$/);
      if (effectivePath === '/') fetchPhotos(page, false, 'library');
      else if (effectivePath === '/garbage') fetchPhotos(page, false, 'garbage');
      else if (albumMatch) fetchPhotos(page, false, 'album', albumMatch[1]);
    }
  }, [page, fetchPhotos, effectivePath])

  useEffect(() => {
    if (selectedPhoto) {
      setExtMetadata(null)
      fetch(`/api/photos/${selectedPhoto.id}/metadata`).then(res => res.ok ? res.json() : null).then(setExtMetadata)
    }
  }, [selectedPhoto])

  useEffect(() => {
    if (showAddToAlbumModal) {
      setTargetAlbumIds([]);
    }
  }, [showAddToAlbumModal]);

  // Sync route and selectedPhoto
  useEffect(() => {
    const assetMatch = location.pathname.match(/^\/asset\/(.+)$/);
    if (!assetMatch) {
      setSelectedPhoto(null);
    } else if (!selectedPhoto && photos.length > 0) {
      // If we land on a direct URL and photos are loaded, set it
      const photo = photos.find(p => p.id === assetMatch[1]);
      if (photo) setSelectedPhoto(photo);
    } else if (!selectedPhoto && currentDuplicateGroup) {
      // If we land on a direct URL inside duplicates
      const photo = currentDuplicateGroup.photos.find(p => p.id === assetMatch[1]);
      if (photo) setSelectedPhoto(photo);
    } else if (!selectedPhoto) {
      // Hard refresh on an asset URL without a pre-loaded grid
      fetch(`/api/photos/${assetMatch[1]}`)
        .then(res => res.ok ? res.json() : null)
        .then(data => {
            if (data) setSelectedPhoto(data);
        });
    }
  }, [location.pathname, photos, selectedPhoto, currentDuplicateGroup]);

  const handlePhotoClick = (photo) => {
    setSelectedPhoto(photo);
    // Persist the FULL background location object (with its internal React Router key)
    // This prevents the background <Routes> from unmounting and remounting all images
    const bgLoc = backgroundLocation || location;
    navigate(`/asset/${photo.id}`, { state: { backgroundLocation: bgLoc }, replace: true });
  };

  const handleCloseLightbox = () => {
    setSelectedPhoto(null);
    // Determine the logical previous route cleanly
    if (backgroundLocation) {
        navigate(backgroundLocation.pathname + (backgroundLocation.search || '') + (backgroundLocation.hash || ''), { replace: true });
    } else {
        navigate('/', { replace: true }); // Fallback cleanly if there's no history
    }
  };

  const handleMoveToGarbage = async (ids) => {
    if (!window.confirm(`Trash ${ids.length} item(s)?`)) return
    
    const res = await fetch('/api/photos/garbage', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) })
    if (res.ok) { setSelectedIds([]); await refreshAndMaintainScroll(); }
  }

  const handleRestore = async (ids) => {
    const res = await fetch('/api/photos/garbage/restore', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) })
    if (res.ok) { setSelectedIds([]); await refreshAndMaintainScroll(); }
  }

  const handlePermanentDelete = async (ids) => {
    if (!window.confirm(`Permanently delete ${ids.length} item(s)? This cannot be undone.`)) return
    const res = await fetch('/api/photos/garbage/permanent', { method: 'DELETE', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) })
    if (res.ok) { setSelectedIds([]); await refreshAndMaintainScroll(); }
  }

  const handleDeleteAlbums = async (ids) => {
    if (!window.confirm(`Delete ${ids.length} album(s)? The photos inside them will not be deleted.`)) return;

    try {
      const deletePromises = ids.map(id => fetch(`/api/albums/${id}`, { method: 'DELETE' }));
      const responses = await Promise.all(deletePromises);
      if (responses.every(r => r.ok)) {
        setSelectedIds([]);
        await fetchAlbums();
      } else {
        throw new Error("One or more albums failed to delete");
      }
    } catch (err) { alert(`Error deleting albums: ${err.message}`); }
  }

  const handleUpload = async (e) => {
    const files = Array.from(e.target.files || [])
    if (!files.length) return

    const currentAlbumId = effectivePath.startsWith('/albums/') && effectivePath !== '/albums' ? effectivePath.split('/').pop() : null;

    const newUploads = files.map(file => ({
      id: Math.random().toString(36).substr(2, 9),
      uploadId: L.Util.stamp(file),
      name: file.name,
      status: 'UPLOADING',
      progress: 0,
      fileSize: file.size,
      contentType: file.type,
      totalChunks: Math.ceil(file.size / CHUNK_SIZE),
      uploadedChunks: 0,
      taskId: null
    }));

    setUploadQueue(prev => [...newUploads, ...prev]);

    for (const uploadItem of newUploads) {
      const file = files.find(f => L.Util.stamp(f) === uploadItem.uploadId);
      if (!file) continue;

      let start = 0;
      let chunkIndex = 0;

      while (start < file.size) {
        const chunk = file.slice(start, start + CHUNK_SIZE);
        const formData = new FormData();
        formData.append('uploadId', uploadItem.uploadId);
        formData.append('fileName', file.name);
        formData.append('fileSize', file.size);
        formData.append('chunkIndex', chunkIndex);
        formData.append('totalChunks', uploadItem.totalChunks);
        formData.append('contentType', file.type);
        formData.append('chunk', chunk);
        if (currentAlbumId) formData.append('albumId', currentAlbumId);

        try {
          const res = await fetch('/api/upload/chunk', { method: 'POST', body: formData });
          const data = await res.json();

          if (data.status === 'completed') {
            setUploadQueue(prev => prev.map(item =>
              item.uploadId === uploadItem.uploadId ? { ...item, status: 'PROCESSING', taskId: data.taskId, uploadedChunks: item.totalChunks, progress: 100 } : item
            ));
            pollTaskStatus(uploadItem.uploadId, data.taskId);
          } else {
            setUploadQueue(prev => prev.map(item =>
              item.uploadId === uploadItem.uploadId ? { ...item, uploadedChunks: item.uploadedChunks + 1, progress: Math.round(((item.uploadedChunks + 1) / item.totalChunks) * 100) } : item
            ));
          }
        } catch (err) {
          console.error("Chunk upload failed:", err);
          setUploadQueue(prev => prev.map(item =>
            item.uploadId === uploadItem.uploadId ? { ...item, status: 'FAILED', error: 'Chunk upload failed' } : item
          ));
          break;
        }

        start += CHUNK_SIZE;
        chunkIndex++;
      }
    }
  };

  const pollTaskStatus = (uploadId, taskId) => {
    const interval = setInterval(async () => {
      try {
        const res = await fetch(`/api/tasks/${taskId}`);
        const task = await res.json();

        if (['COMPLETED', 'FAILED', 'IGNORED', 'TRASHED'].includes(task.status)) {
          clearInterval(interval);
          let finalStatus = task.status;
          if (task.status === 'IGNORED') finalStatus = 'ALREADY IMPORTED';
          if (task.status === 'TRASHED') finalStatus = 'ALREADY IMPORTED AND TRASHED';
          
          setUploadQueue(prev => prev.map(item =>
            item.uploadId === uploadId ? { ...item, status: finalStatus, error: task.errorMessage, progress: 100 } : item
          ));
          if (task.status === 'COMPLETED') await refreshAndMaintainScroll();
        }
      } catch (err) {
        clearInterval(interval);
        console.error("Polling failed:", err);
      }
    }, 3000);
  };

  const handleCreateAlbum = async () => {
    setAlbumCreationError(null);
    if (!newAlbumName.trim()) {
      setAlbumCreationError("Album name cannot be empty.");
      return;
    }
    try {
      const res = await fetch('/api/albums', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: newAlbumName, description: newAlbumDescription })
      });
      if (!res.ok) throw new Error(`Failed to create album: ${res.status}`);
      setNewAlbumName('');
      setNewAlbumDescription('');
      setShowCreateAlbumModal(false);
      fetchAlbums();
    } catch (err) {
      setAlbumCreationError(err.message);
      console.error("Error creating album:", err);
    }
  };

  const handleCreateShareLink = async () => {
    setShareLinkCreationError(null);
    if (!newShareLinkName.trim()) {
      setShareLinkCreationError("Link name cannot be empty.");
      return;
    }
    // Validate for URL-friendly characters (alphanumeric, hyphen, underscore)
    const urlFriendlyRegex = /^[a-zA-Z0-9_-]+$/;
    if (!urlFriendlyRegex.test(newShareLinkName)) {
      setShareLinkCreationError("Link name can only contain letters, numbers, hyphens, and underscores.");
      return;
    }
    if (newShareLinkExpiresInDays <= 0) {
      setShareLinkCreationError("Link availability must be at least 1 day.");
      return;
    }
    if (!albumIdForShareLink) {
      setShareLinkCreationError("No album selected for sharing.");
      return;
    }

    try {
      const res = await fetch('/api/share', { // Corrected endpoint to /api/share
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ 
          name: newShareLinkName, 
          password: newShareLinkPassword, 
          daysAvailable: newShareLinkExpiresInDays, // Corrected to daysAvailable
          albumId: albumIdForShareLink // Pass albumId
        }) 
      });
      if (!res.ok) {
        const errorData = await res.json();
        throw new Error(errorData.message || `Failed to create share link: ${res.status}`);
      }
      alert(`Share link "${newShareLinkName}" created successfully!`);
      setNewShareLinkName('');
      setNewShareLinkPassword('');
      setNewShareLinkExpiresInDays(7); // Reset to default
      setAlbumIdForShareLink(null); // Clear album ID
      setShowCreateShareLinkModal(false);
      // Optionally refresh share links or show a success message
    } catch (err) {
      setShareLinkCreationError(err.message);
      console.error("Error creating share link:", err);
    }
  };

  const renderAlbums = () => (
    <div className="content-body">
      <div className="album-grid">
        {albums.map(album => {
          const isSelected = selectedIds.includes(album.id);
          return (
            <div 
              key={album.id} 
              className={`album-card ${isSelected ? 'selected' : ''}`} 
              onClick={() => {
                if (selectedIds.length > 0) {
                  setSelectedIds(v => v.includes(album.id) ? v.filter(i => i !== album.id) : [...v, album.id]);
                } else {
                  navigate(`/albums/${album.id}`);
                }
              }}
            >
              <div 
                className="select-overlay" 
                onClick={(e) => {
                  e.stopPropagation();
                  setSelectedIds(v => v.includes(album.id) ? v.filter(i => i !== album.id) : [...v, album.id]);
                }}
              >
                <div className={`check-circle ${isSelected ? 'checked' : ''}`}>
                  {isSelected && <Check size={14} />}
                </div>
              </div>
              
              <div className="album-cover">
                {album.coverPhoto ? <img src={album.coverPhoto.url} alt={album.name} /> : <div className="album-placeholder"><ImageIcon size={48} color="#888" /></div>}
              </div>
              <div className="album-info">
                <h3>{album.name}</h3>
                <p>{album.photoCount} photos</p>
              </div>
            </div>
          );
        })}
        {albums.length === 0 && <div style={{textAlign: 'center', padding: '50px', color: '#888', gridColumn: '1/-1'}}>No albums created yet. Click "Create Album" to get started!</div>}
      </div>
    </div>
  );

  const handleAddPhotosToAlbums = async () => {
    if (selectedIds.length === 0 || targetAlbumIds.length === 0) return;

    const addPromises = targetAlbumIds.map(albumId => 
      fetch(`/api/albums/${albumId}/photos`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ photoIds: selectedIds })
      })
    );

    try {
      const responses = await Promise.all(addPromises);
      const allOk = responses.every(res => res.ok);

      if (allOk) {
        alert(`Added ${selectedIds.length} photo(s) to ${targetAlbumIds.length} album(s).`);
        setSelectedIds([]);
        setShowAddToAlbumModal(false);
        await fetchAlbums();
      } else {
        throw new Error('One or more requests to add photos failed.');
      }
    } catch (err) {
      alert(`Error: ${err.message}`);
    }
  };

  const handleRemovePhotosFromAlbum = async (albumId) => {
    if (!albumId || selectedIds.length === 0) return;
    if (!window.confirm(`Remove ${selectedIds.length} photo(s) from this album?`)) return;

    try {
      const res = await fetch(`/api/albums/${albumId}/photos`, {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ photoIds: selectedIds })
      });
      if (res.ok) {
        setSelectedIds([]);
        await refreshAndMaintainScroll();
      } else throw new Error(`Failed to remove photos: ${res.status}`);
    } catch (err) { alert(`Error removing photos: ${err.message}`); }
  };

  const handleSetCoverPhoto = async (albumId, photoId) => {
    if (!albumId || !photoId) return;
    try {
      const res = await fetch(`/api/albums/${albumId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ coverPhotoId: photoId })
      });
      if (res.ok) {
        setSelectedIds([]);
        await fetchAlbums();
      } else throw new Error(`Failed to set cover photo: ${res.status}`);
    } catch (err) { alert(`Error setting cover photo: ${err.message}`); }
  };

  const handleUpdateDate = async (photoId, editData) => { // Changed newDate to editData
    try {
      const res = await fetch(`/api/photos/${photoId}/date`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(editData) // Pass editData directly
      });
      if (res.ok) {
        const updatedPhoto = await res.json();
        setSelectedPhoto(updatedPhoto);
        await refreshAndMaintainScroll();
      } else throw new Error(`Failed to update date: ${err.status}`);
    } catch (err) { alert(`Error updating date: ${err.message}`); }
  };

  const handleEditPhoto = async (photoId, editData) => {
    try {
      const res = await fetch(`/api/photos/${photoId}/edit`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(editData)
      });
      if (res.ok) {
        const updatedPhoto = await res.json();
        if (!editData.saveAsNew) {
           setSelectedPhoto(updatedPhoto);
        } else {
           alert("New photo is being processed and will appear shortly!");
           handleCloseLightbox();
        }
        await refreshAndMaintainScroll();
      } else {
        throw new Error(`Failed to edit photo: ${res.status}`);
      }
    } catch (err) {
      alert(`Error editing photo: ${err.message}`);
    }
  };

  const renderAddToAlbumModal = () => (
    <div className="modal-overlay">
      <div className="modal-content">
        <h2>Add to Album</h2>
        <p>Select albums to add {selectedIds.length} photo(s) to:</p>
        <div className="album-list-selection">
          {albums.length > 0 ? albums.map(album => (
            <div 
              key={album.id} 
              className={`album-select-item ${targetAlbumIds.includes(album.id) ? 'selected' : ''}`} 
              onClick={() => {
                setTargetAlbumIds(prev => 
                  prev.includes(album.id) 
                    ? prev.filter(id => id !== album.id) 
                    : [...prev, album.id]
                );
              }}
            >
              <div className="album-cover-small">
                {album.coverPhoto ? <img src={album.coverPhoto.url} alt={album.name} /> : <div className="album-placeholder-small"><ImageIcon size={24} color="#888" /></div>}
              </div>
              <div className="album-select-info"><h3>{album.name}</h3><p>{album.photoCount} photos</p></div>
              {targetAlbumIds.includes(album.id) && <div className="check-circle checked" style={{ marginLeft: 'auto', width: '22px', height: '22px' }}><Check size={14} /></div>}
            </div>
          )) : <p>No albums available. Create one first!</p>}
        </div>
        <div className="modal-actions">
          <button className="btn" onClick={() => setShowAddToAlbumModal(false)}>Cancel</button>
          <button className="btn btn-primary" onClick={handleAddPhotosToAlbums} disabled={targetAlbumIds.length === 0}>Add to Albums</button>
        </div>
      </div>
    </div>
  );

  const renderCreateShareLinkModal = () => {
    const currentAlbum = albums.find(a => a.id === albumIdForShareLink);
    return (
      <div className="modal-overlay">
        <div className="modal-content">
          <h2>Create Share Link for "{currentAlbum?.name}"</h2>
          {shareLinkCreationError && <div className="error-message">{shareLinkCreationError}</div>}
          <input 
            type="text" 
            placeholder="Link Name (URL friendly)" 
            value={newShareLinkName} 
            onChange={(e) => setNewShareLinkName(e.target.value)} 
            className="modal-input" 
          />
          <input 
            type="password" 
            placeholder="Password (optional)" 
            value={newShareLinkPassword} 
            onChange={(e) => setNewShareLinkPassword(e.target.value)} 
            className="modal-input" 
            style={{ marginTop: '10px' }}
          />
          <input 
            type="number" 
            placeholder="Available for (days)" 
            value={newShareLinkExpiresInDays} 
            onChange={(e) => setNewShareLinkExpiresInDays(Math.max(1, parseInt(e.target.value) || 1))} // Ensure at least 1 day
            className="modal-input" 
            min="1"
            style={{ marginTop: '10px' }}
          />
          <div className="modal-actions">
            <button className="btn" onClick={() => { setShowCreateShareLinkModal(false); setAlbumIdForShareLink(null); }}>Cancel</button>
            <button className="btn btn-primary" onClick={handleCreateShareLink}>Create Link</button>
          </div>
        </div>
      </div>
    );
  };

  const AlbumDetail = () => {
    const { albumId } = useParams();
    const currentAlbum = albums.find(a => a.id === albumId);

    if (!currentAlbum && albums.length > 0) return <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>Album not found.</div>;
    if (!currentAlbum) return <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>Loading album...</div>;

    return (
      <div className="content-body">
        <div className="album-detail-header">
          <h2>{currentAlbum.name}</h2>
          <p>{currentAlbum.description}</p>
          <span className="count">{currentAlbum.photoCount} photos</span>
        </div>
        {photos.length > 0 ? (
          <PhotoGrid 
            photos={photos} 
            hasMore={hasMore} 
            setPage={setPage} 
            selectedIds={selectedIds} 
            setSelectedIds={setSelectedIds} 
            setSelectedPhoto={handlePhotoClick} 
          />
        ) : <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>This album is empty. Add some photos!</div>}
      </div>
    );
  };

  const HeaderWrapper = () => {
    const effectivePath = backgroundLocation?.pathname || location.pathname;
    const routeAlbumId = effectivePath.startsWith('/albums/') && effectivePath !== '/albums' ? effectivePath.split('/').pop() : null;
    
    const singleSelectedPhoto = (selectedIds.length === 1) 
      ? photos.find(p => p.id === selectedIds[0]) || currentDuplicateGroup?.photos?.find(p => p.id === selectedIds[0])
      : null;
    const canSetCoverPhoto = singleSelectedPhoto && !singleSelectedPhoto.contentType.startsWith('video');

    return (
      <Header
        selectedIds={selectedIds}
        setSelectedIds={setSelectedIds}
        pathname={effectivePath}
        handleRestore={handleRestore}
        handlePermanentDelete={handlePermanentDelete}
        handleRemovePhotosFromAlbum={() => handleRemovePhotosFromAlbum(routeAlbumId)}
        handleMoveToGarbage={handleMoveToGarbage}
        setShowAddToAlbumModal={setShowAddToAlbumModal}
        setShowCreateAlbumModal={setShowCreateAlbumModal}
        setShowCreateShareLinkModal={(albumId) => { // Modified to accept albumId
          setAlbumIdForShareLink(albumId);
          setShowCreateShareLinkModal(true);
        }}
        handleUpload={handleUpload}
        albumId={routeAlbumId}
        canSetCoverPhoto={canSetCoverPhoto}
        handleSetCoverPhoto={handleSetCoverPhoto}
        handleDeleteAlbums={handleDeleteAlbums}
        navigate={navigate}
        duplicatesPage={duplicatesPage}
        fetchDuplicates={fetchDuplicates}
        isLastDuplicateGroup={isLastDuplicateGroup}
      />
    );
  };

  const getActivePhotosContext = () => {
    if (effectivePath.startsWith('/duplicates')) {
      return currentDuplicateGroup ? currentDuplicateGroup.photos : [];
    }
    return photos;
  };

  return (
    <div className="app-container">
      <Sidebar pathname={backgroundLocation?.pathname || location.pathname} />

      <main className="main-content" ref={mainContentRef}>
        <HeaderWrapper />

        <Routes location={backgroundLocation || location}>
          <Route path="/" element={
            <PhotoGrid photos={photos} hasMore={hasMore} setPage={setPage} selectedIds={selectedIds} setSelectedIds={setSelectedIds} setSelectedPhoto={handlePhotoClick} />
          } />
          <Route path="/garbage" element={
            <PhotoGrid photos={photos} hasMore={hasMore} setPage={setPage} selectedIds={selectedIds} setSelectedIds={setSelectedIds} setSelectedPhoto={handlePhotoClick} />
          } />
          <Route path="/map" element={
            <div className="map-view-container">
              <MapContainer center={[20, 0]} zoom={2} zoomControl={false}>
                <TileLayer url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" />
                {mapAssets.map(asset => (
                  <Marker key={asset.id} position={[asset.latitude, asset.longitude]}>
                    <Popup><div className="map-popup-asset" onClick={() => handlePhotoClick(asset)}><img src={`/api/photos/${asset.id}/thumbnail`} alt="Asset" style={{ width: '120px', borderRadius: '4px' }} /></div></Popup>
                  </Marker>
                ))}
              </MapContainer>
            </div>
          } />
          <Route path="/duplicates/*" element={
            <div className="content-body" style={{ textAlign: 'center', paddingTop: '40px' }}>
              {currentDuplicateGroup ? (
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
                          <img src={p.url} loading="lazy" style={{ width: '100%', height: '100%', objectFit: 'contain', background: '#e0e0e5' }} />
                        </div>
                      )
                    })}
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'center', gap: '20px', marginTop: '40px' }}>
                     <button className="btn" onClick={() => fetchDuplicates(duplicatesPage - 1)} disabled={duplicatesPage === 0}>Previous Group</button>
                     <button className="btn" style={{color: '#007aff', borderColor: '#007aff'}} onClick={handleFinishComparison}>Finish Comparison</button>
                     <button className="btn btn-primary" onClick={() => fetchDuplicates(duplicatesPage + 1)} disabled={isLastDuplicateGroup}>Next Group</button>
                  </div>
                </div>
              ) : <div style={{textAlign: 'center', padding: '50px', color: '#888'}}>No duplicates found. All clean!</div>}
            </div>
          } />
          <Route path="/albums" element={renderAlbums()} />
          <Route path="/albums/:albumId" element={<AlbumDetail />} />
          <Route path="/share-links" element={
            <div className="content-body" style={{ textAlign: 'center', paddingTop: '40px' }}>
              <h2>Manage Share Links</h2>
              <p>This is where you will manage your share links.</p>
            </div>
          } />
          <Route path="/share/:name" element={<ShareLinkPage />} />
          <Route path="/share/:name/login" element={<ShareLoginPage />} /> {/* New route for ShareLoginPage */}
        </Routes>
      </main>

      {uploadQueue.length > 0 && (
        <div className="upload-queue">
          <div className="upload-queue-header"><span>Uploads ({uploadQueue.length})</span><X size={16} onClick={() => setUploadQueue([])} style={{cursor: 'pointer', opacity: 0.5}}/></div>
          <div className="upload-list">
            {uploadQueue.map(item => (
              <div key={item.id} className="upload-item">
                <div className="upload-item-info">
                  <span className="upload-item-name" title={item.name}>{item.name}</span>
                  <span className={`status-text ${item.status?.replace(/\s/g, '')}`}>{item.status}</span>
                </div>
                {['UPLOADING', 'PROCESSING', 'COMPLETED'].includes(item.status) && (
                  <div style={{ width: '100%', height: '4px', background: '#e0e0e5', borderRadius: '2px', marginTop: '6px', overflow: 'hidden' }}>
                    <div style={{ height: '100%', background: 'var(--primary-color)', width: `${item.progress || 0}%`, transition: 'width 0.2s ease' }} />
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Render Lightbox independently if the URL matches /asset/ */}
      {location.pathname.startsWith('/asset/') && selectedPhoto && (
        <Lightbox 
          selectedPhoto={selectedPhoto} 
          onClose={handleCloseLightbox} 
          extMetadata={extMetadata} 
          pathname={backgroundLocation?.pathname || location.pathname} 
          handleMoveToGarbage={handleMoveToGarbage} 
          handleRestore={handleRestore} 
          handlePermanentDelete={handlePermanentDelete}
          photos={getActivePhotosContext()}
          onNavigate={handlePhotoClick}
          onUpdateDate={handleUpdateDate}
          onEditPhoto={handleEditPhoto}
        />
      )}

      {showAddToAlbumModal && renderAddToAlbumModal()}
      {showCreateAlbumModal && (
        <div className="modal-overlay">
          <div className="modal-content">
            <h2>Create New Album</h2>
            {albumCreationError && <div className="error-message">{albumCreationError}</div>}
            <input type="text" placeholder="Album Name" value={newAlbumName} onChange={(e) => setNewAlbumName(e.target.value)} className="modal-input" />
            <textarea placeholder="Description (optional)" value={newAlbumDescription} onChange={(e) => setNewAlbumDescription(e.target.value)} className="modal-textarea" />
            <div className="modal-actions">
              <button className="btn" onClick={() => setShowCreateAlbumModal(false)}>Cancel</button>
              <button className="btn btn-primary" onClick={handleCreateAlbum}>Create</button>
            </div>
          </div>
        </div>
      )}
      {showCreateShareLinkModal && renderCreateShareLinkModal()}
    </div>
  )
}

export default App
