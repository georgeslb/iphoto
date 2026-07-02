import React, { useState, useCallback } from 'react'; // Removed useRef from here
import { X } from 'lucide-react';
import L from 'leaflet';

const CHUNK_SIZE = 1024 * 1024 * 5; // 5MB chunks

function UploadManager({ refreshAndMaintainScroll, effectivePath, children, fileInputRef }) { // Accept fileInputRef as prop
  const [uploadQueue, setUploadQueue] = useState([]);
  // Removed: const fileInputRef = useRef(null); // This is now passed as a prop

  const pollTaskStatus = useCallback((uploadId, taskId) => {
    const interval = setInterval(async () => {
      try {
        const res = await fetch(`/api/tasks/${taskId}`);
        if (!res.ok) {
          throw new Error(`HTTP error! status: ${res.status}`);
        }
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
        setUploadQueue(prev => prev.map(item =>
            item.uploadId === uploadId ? { ...item, status: 'FAILED', error: `Polling failed: ${err.message}` } : item
        ));
      }
    }, 3000);
  }, [refreshAndMaintainScroll]);

  // Refactored upload logic to accept files directly
  const handleUploadFiles = useCallback(async (filesToUpload) => {
    if (!filesToUpload.length) {
      if (fileInputRef.current) {
        fileInputRef.current.value = null; // Reset file input even if no files selected
      }
      return;
    }

    const currentAlbumId = effectivePath.startsWith('/albums/') && effectivePath !== '/albums' ? effectivePath.split('/').pop() : null;

    const newUploadItems = filesToUpload.map(file => ({
      id: Math.random().toString(36).substr(2, 9),
      uploadId: L.Util.stamp(file),
      name: file.name,
      status: 'UPLOADING',
      progress: 0,
      fileSize: file.size,
      contentType: file.type,
      totalChunks: Math.ceil(file.size / CHUNK_SIZE),
      uploadedChunks: 0,
      taskId: null,
      error: null,
      file: file, // Store the actual File object
    }));

    // Add new items to the queue or update existing ones if retrying
    setUploadQueue(prev => {
      const updatedPrev = prev.filter(item => !newUploadItems.some(newItem => newItem.uploadId === item.uploadId));
      return [...newUploadItems, ...updatedPrev];
    });

    for (const uploadItem of newUploadItems) {
      const file = uploadItem.file; // Get the file from the upload item

      let start = 0;
      let chunkIndex = 0;
      let uploadFailed = false;

      while (start < file.size && !uploadFailed) {
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
          if (!res.ok) {
            const errorText = await res.text();
            throw new Error(`Server responded with status ${res.status}: ${errorText}`);
          }
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
              item.uploadId === uploadItem.uploadId ? { ...item, status: 'FAILED', error: `Upload failed: ${err.message}` } : item
          ));
          uploadFailed = true;
        }

        start += CHUNK_SIZE;
        chunkIndex++;
      }
    }

    // Reset the file input value after all files have been processed or if an error occurred
    if (fileInputRef.current) {
      fileInputRef.current.value = null;
    }
  }, [effectivePath, pollTaskStatus, refreshAndMaintainScroll, fileInputRef]); // Add fileInputRef to dependencies

  // Original handleUpload for initial file selection
  const handleInitialUpload = useCallback((e) => {
    handleUploadFiles(Array.from(e.target.files || []));
  }, [handleUploadFiles]);

  // Function to retry a failed upload
  const handleRetry = useCallback((uploadId) => {
    setUploadQueue(prev => {
      const itemToRetry = prev.find(item => item.uploadId === uploadId);
      if (itemToRetry && itemToRetry.status === 'FAILED' && itemToRetry.file) {
        // Reset status and error, then re-upload the single file
        const updatedQueue = prev.map(item =>
          item.uploadId === uploadId ? { ...item, status: 'UPLOADING', progress: 0, error: null } : item
        );
        handleUploadFiles([itemToRetry.file]); // Call the refactored function with the single file
        return updatedQueue;
      }
      return prev;
    });
  }, [handleUploadFiles]);

  return (
    <>
      {children({ handleUpload: handleInitialUpload, uploadQueue, setUploadQueue, fileInputRef })} {/* Pass fileInputRef */}
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
                    {item.error && (
                      <div className="upload-item-error">
                        {item.error}
                        {item.status === 'FAILED' && (
                          <button onClick={() => handleRetry(item.uploadId)} className="retry-button">Retry</button>
                        )}
                      </div>
                    )}
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