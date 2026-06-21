import React, { useState, useCallback } from 'react';
import { X } from 'lucide-react';
import L from 'leaflet';

const CHUNK_SIZE = 1024 * 1024 * 5; // 5MB chunks

function UploadManager({ refreshAndMaintainScroll, effectivePath, children }) { // Added children prop
  const [uploadQueue, setUploadQueue] = useState([]);

  const pollTaskStatus = useCallback((uploadId, taskId) => {
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
          if (task.status === 'COMPLETED') {
            refreshAndMaintainScroll();
          }
        }
      } catch (err) {
        clearInterval(interval);
        console.error("Polling failed:", err);
      }
    }, 3000);
  }, [refreshAndMaintainScroll]);

  const handleUpload = useCallback(async (e) => {
    const files = Array.from(e.target.files || []);
    if (!files.length) return;

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
  }, [effectivePath, pollTaskStatus, refreshAndMaintainScroll]);

  return (
    <>
      {children({ handleUpload, uploadQueue, setUploadQueue })} {/* Render children with props */}
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
    </>
  );
}

export default UploadManager;