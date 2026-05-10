import React, { useState, useEffect, useRef, useCallback } from 'react'
import {
  Image as ImageIcon, Search, X, Library, Copy, Upload, Map as MapIcon,
  Calendar, Info, Camera, MapPin, ChevronRight, ChevronDown, Trash2, Zap, Clock, Check, RotateCcw
} from 'lucide-react'
import { MapContainer, TileLayer, Marker, Popup } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'
import './App.css'

import L from 'leaflet'
import markerIcon from 'leaflet/dist/images/marker-icon.png'
import markerShadow from 'leaflet/dist/images/marker-shadow.png'
let DefaultIcon = L.icon({ iconUrl: markerIcon, shadowUrl: markerShadow, iconSize: [25, 41], iconAnchor: [12, 41] })
L.Marker.prototype.options.icon = DefaultIcon

function App() {
  const [photos, setPhotos] = useState([])
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(true)
  const [uploadQueue, setUploadQueue] = useState([])
  const [selectedPhoto, setSelectedPhoto] = useState(null)
  const [extMetadata, setExtMetadata] = useState(null)
  const [activeTab, setActiveTab] = useState('library')
  const [hoveredVideoId, setHoveredVideoId] = useState(null)
  const [mapAssets, setMapAssets] = useState([])
  const [selectionMode, setSelectionMode] = useState(false)
  const [selectedIds, setSelectedIds] = useState([])

  const fetchPhotos = useCallback(async (pageNum, reset = false) => {
    try {
      const endpoint = activeTab === 'garbage' ? '/api/photos/garbage' : '/api/photos';
      const sort = activeTab === 'garbage' ? 'garbageAt,desc' : 'dateTaken,desc';
      const res = await fetch(`${endpoint}?page=${pageNum}&size=50&sort=${sort}`)
      const data = await res.json()
      setPhotos(prev => reset ? data.content : [...prev, ...data.content])
      setHasMore(data && data.last === false)
    } catch (err) { console.error("Fetch failed:", err) }
  }, [activeTab])

  useEffect(() => {
    setPhotos([]); setPage(0); setHasMore(true); setSelectionMode(false); setSelectedIds([]);
    if (activeTab === 'library' || activeTab === 'garbage') fetchPhotos(0, true)
    if (activeTab === 'map') fetch('/api/photos/locations').then(r => r.json()).then(setMapAssets)
  }, [activeTab, fetchPhotos])

  useEffect(() => { if (page > 0) fetchPhotos(page) }, [page, fetchPhotos])

  useEffect(() => {
    if (selectedPhoto) {
      setExtMetadata(null)
      fetch(`/api/photos/${selectedPhoto.id}/metadata`).then(res => res.ok ? res.json() : null).then(setExtMetadata)
    }
  }, [selectedPhoto])

  const handleMoveToGarbage = async (ids) => {
    if (!window.confirm(`Trash ${ids.length} item(s)?`)) return
    const res = await fetch('/api/photos/garbage', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) })
    if (res.ok) { setSelectionMode(false); setSelectedIds([]); fetchPhotos(0, true); }
  }

  const handleRestore = async (ids) => {
    const res = await fetch('/api/photos/garbage/restore', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) })
    if (res.ok) { setSelectionMode(false); setSelectedIds([]); fetchPhotos(0, true); }
  }

  const handlePermanentDelete = async (ids) => {
    if (!window.confirm(`Permanently delete ${ids.length} item(s)? This cannot be undone.`)) return
    const res = await fetch('/api/photos/garbage/permanent', { method: 'DELETE', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ids) })
    if (res.ok) { setSelectionMode(false); setSelectedIds([]); fetchPhotos(0, true); }
  }

  const handleUpload = async (e) => {
    const files = Array.from(e.target.files || [])
    if (!files.length) return
    const newEntries = files.map(f => ({ id: Math.random().toString(36).substr(2, 9), name: f.name, status: 'UPLOADING' }))
    setUploadQueue(prev => [...newEntries, ...prev])
    for (const file of files) {
      const fd = new FormData(); fd.append('file', file)
      try {
        const res = await fetch('/api/upload', { method: 'POST', body: fd })
        const data = await res.json()
        setUploadQueue(q => q.map(it => it.name === file.name ? { ...it, status: 'PROCESSING', taskId: data.taskId } : it))
        const interval = setInterval(async () => {
          try {
            const tRes = await fetch(`/api/tasks/${data.taskId}`)
            const task = await tRes.json()
            if (['COMPLETED', 'FAILED', 'IGNORED'].includes(task.status)) {
              clearInterval(interval)
              const finalStatus = task.status === 'IGNORED' ? 'ALREADY IMPORTED' : task.status
              setUploadQueue(q => q.map(it => it.name === file.name ? { ...it, status: finalStatus } : it))
              if (task.status === 'COMPLETED' && activeTab === 'library') fetchPhotos(0, true)
            }
          } catch (err) { clearInterval(interval) }
        }, 3000)
      } catch (e) { setUploadQueue(q => q.map(it => it.name === file.name ? { ...it, status: 'FAILED' } : it)) }
    }
  }

  const renderGrid = () => {
    const groups = {}
    photos.forEach(p => { const key = p.groupDate || 'Other'; if (!groups[key]) groups[key] = []; groups[key].push(p); })
    return (
      <div className="content-body">
        {Object.keys(groups).map(day => (
          <div key={day} className="day-group">
            <div className="day-header"><h3>{day}</h3></div>
            <div className="photo-grid">
              {groups[day].map(p => {
                const aspectRatio = (p.width && p.height) ? (p.width / p.height) : 1;
                return (
                  <div
                    key={p.id}
                    className={`photo-card ${selectedIds.includes(p.id) ? 'selected' : ''}`}
                    style={{ flexBasis: `${aspectRatio * 220}px` }}
                    onClick={() => selectionMode ? setSelectedIds(v => v.includes(p.id) ? v.filter(i => i !== p.id) : [...v, p.id]) : setSelectedPhoto(p)}
                    onMouseEnter={() => !selectionMode && p.contentType?.startsWith('video') && setHoveredVideoId(p.id)}
                    onMouseLeave={() => setHoveredVideoId(null)}
                  >
                    {selectionMode && <div className="select-overlay"><div className={`check-circle ${selectedIds.includes(p.id) ? 'checked' : ''}`}>{selectedIds.includes(p.id) && <Check size={14} />}</div></div>}
                    {hoveredVideoId === p.id ?
                      <video src={`/api/photos/${p.id}/stream#t=0.5`} autoPlay muted loop playsInline className="card-video" /> :
                      <img src={p.url} loading="lazy" />
                    }
                    {p.contentType?.startsWith('video') && hoveredVideoId !== p.id && <div className="video-badge">▶</div>}
                  </div>
                )
              })}
            </div>
          </div>
        ))}
        {hasMore && <div style={{ padding: '40px 0', textAlign: 'center' }}><button className="btn" onClick={() => setPage(p => p + 1)}>Load More</button></div>}
      </div>
    )
  }

  return (
    <div className="app-container">
      <aside className="sidebar">
        <div className="sidebar-logo"><ImageIcon size={28} color="#007AFF" /> <h2>iPhoto</h2></div>
        <nav className="nav-section">
          <div className={`nav-item ${activeTab === 'library' ? 'active' : ''}`} onClick={() => setActiveTab('library')}><Library size={20} /> Library</div>
          <div className={`nav-item ${activeTab === 'map' ? 'active' : ''}`} onClick={() => setActiveTab('map')}><MapIcon size={20} /> Map</div>
          <div className={`nav-item ${activeTab === 'duplicates' ? 'active' : ''}`} onClick={() => setActiveTab('duplicates')}><Copy size={20} /> Duplicates</div>
          <div className={`nav-item ${activeTab === 'garbage' ? 'active' : ''}`} onClick={() => setActiveTab('garbage')}><Trash2 size={20} /> Garbage</div>
        </nav>
      </aside>

      <main className="main-content">
        <header className="header">
          {selectionMode ? (
            <div style={{ display: 'flex', gap: '12px', alignItems: 'center', flex: 1 }}>
               <button className="btn" onClick={() => { setSelectionMode(false); setSelectedIds([]) }}><X size={18}/> Cancel</button>
               <span style={{fontWeight: 600}}>{selectedIds.length} selected</span>
               {activeTab === 'garbage' ?
                 <>
                   <button className="btn" style={{color: '#34c759', marginLeft: 'auto'}} onClick={() => handleRestore(selectedIds)} disabled={selectedIds.length === 0}><RotateCcw size={18}/> Restore</button>
                   <button className="btn" style={{color: '#ff3b30'}} onClick={() => handlePermanentDelete(selectedIds)} disabled={selectedIds.length === 0}><Trash2 size={18}/> Delete Permanently</button>
                 </>
                 :
                 <button className="btn" style={{color: '#ff3b30', marginLeft: 'auto'}} onClick={() => handleMoveToGarbage(selectedIds)} disabled={selectedIds.length === 0}><Trash2 size={18}/> Move to Garbage</button>
               }
            </div>
          ) : (
            <div style={{ display: 'flex', gap: '12px' }}>
              <button className="btn" onClick={() => setSelectionMode(true)}><Check size={18}/> Select</button>
              <button className="btn btn-primary" onClick={() => document.getElementById('fIn').click()}><Upload size={18} /> Upload</button>
            </div>
          )}
          <input id="fIn" type="file" multiple onChange={handleUpload} style={{ display: 'none' }} />
        </header>

        {(activeTab === 'library' || activeTab === 'garbage') && renderGrid()}
        {activeTab === 'map' && (
          <div className="map-view-container">
            <MapContainer center={[20, 0]} zoom={2} zoomControl={false}>
              <TileLayer url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" />
              {mapAssets.map(asset => (
                <Marker key={asset.id} position={[asset.latitude, asset.longitude]}>
                  <Popup><div className="map-popup-asset" onClick={() => setSelectedPhoto(asset)}><img src={`/api/photos/${asset.id}/thumbnail`} alt="Asset" style={{ width: '120px', borderRadius: '4px' }} /></div></Popup>
                </Marker>
              ))}
            </MapContainer>
          </div>
        )}
      </main>

      {uploadQueue.length > 0 && (
        <div className="upload-queue">
          <div className="upload-queue-header"><span>Progress</span><X size={16} onClick={() => setUploadQueue([])} style={{cursor: 'pointer', opacity: 0.5}}/></div>
          <div className="upload-list">{uploadQueue.map(item => (<div key={item.id} className="upload-item"><div className="upload-item-info"><span className="upload-item-name">{item.name}</span><span className={`status-text ${item.status?.replace(/\s/g, '')}`}>{item.status}</span></div></div>))}</div>
        </div>
      )}

      {selectedPhoto && !selectionMode && (
        <div className="lightbox-overlay" onClick={() => setSelectedPhoto(null)}>
          <div className="lightbox-container" onClick={e => e.stopPropagation()}>
            <div className="lightbox-content">
              {selectedPhoto.contentType?.startsWith('video') ? <video src={`/api/photos/${selectedPhoto.id}/stream`} controls autoPlay /> : <img src={`/api/photos/${selectedPhoto.id}/original`} />}
              {activeTab === 'garbage' ? (
                <>
                  <button className="btn" style={{position: 'absolute', bottom: '20px', left: '20px', background: '#34c759', color: 'white', border: 'none'}} onClick={() => { handleRestore([selectedPhoto.id]); setSelectedPhoto(null); }}>
                    <RotateCcw size={18}/> Restore Asset
                  </button>
                  <button className="btn" style={{position: 'absolute', bottom: '20px', left: '180px', background: '#ff3b30', color: 'white', border: 'none'}} onClick={() => { handlePermanentDelete([selectedPhoto.id]); setSelectedPhoto(null); }}>
                    <Trash2 size={18}/> Delete Permanently
                  </button>
                </>
              ) : (
                <button className="btn" style={{position: 'absolute', bottom: '20px', left: '20px', background: 'rgba(255,255,255,0.2)', color: 'white', border: 'none'}} onClick={() => { handleMoveToGarbage([selectedPhoto.id]); setSelectedPhoto(null); }}>
                  <Trash2 size={18}/> Move to Garbage
                </button>
              )}
            </div>
            <div className="metadata-sidebar">
              <div className="sidebar-header"><h3>Information</h3><X size={20} onClick={() => setSelectedPhoto(null)} style={{cursor: 'pointer', opacity: 0.6}} /></div>
              <div className="sidebar-scroll-content">
                <div className="metadata-group"><label>File Name</label><p>{selectedPhoto.title}</p></div>
                <div className="metadata-group"><label><Calendar size={14} /> Date Taken</label><p>{selectedPhoto.date}</p></div>
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
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

export default App
