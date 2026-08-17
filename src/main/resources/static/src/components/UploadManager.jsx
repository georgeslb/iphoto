import React, { useState, useCallback } from 'react';
import { X } from 'lucide-react';
import L from 'leaflet';
import { useLocale } from '../LocaleContext.jsx'; // Import useLocale
import UploadPreviewModal from './UploadPreviewModal.jsx';

const CHUNK_SIZE = 1024 * 1024 * 5; // 5MB chunks

function UploadManager({ refreshAndMaintainScroll, effectivePath, children, fileInputRef }) {
  const { messages } = useLocale(); // Use the useLocale hook
  const [uploadQueue, setUploadQueue] = useState([]);
  const [previewPhotoId, setPreviewPhotoId] = useState(null);

  const pollTaskStatus = useCallback(async (uploadId, taskId) => {
    const interval = setInterval(async () => {
      try {
        const res = await fetch(`/api/tasks/${taskId}`);
        if (!res.ok) {
          throw new Error(messages["serverRespondedWithError"](res.status, res.statusText));
        }
        const task = await res.json();
        const isTerminal = ['COMPLETED', 'FAILED', 'IGNORED', 'TRASHED'].includes(task.status);

        if (isTerminal || task.status === 'PENDING') {
          setUploadQueue(prev => prev.map(item =>
              item.uploadId === uploadId ? { ...item, status: task.status, error: task.errorMessage, progress: 100, photoId: task.photoId } : item
          ));

          if (isTerminal) {
            clearInterval(interval);
            if (task.status === 'COMPLETED') {
              refreshAndMaintainScroll();
            }
          }
        }
      } catch (err) {
        clearInterval(interval);
        console.error(messages["pollingFailed"](err.message));
        setUploadQueue(prev => prev.map(item =>
              item.uploadId === uploadId ? { ...item, status: 'FAILED', error: messages["pollingFailed"](err.message) } : item
          ));
      }
    }, 3000);
  }, [refreshAndMaintainScroll, messages]);

  // Refactored upload logic to accept files directly
  const handleUploadFiles = useCallback(async (filesToUpload) => {
    if (!filesToUpload.length) {
      if (fileInputRef.current) {
        fileInputRef.current.value = null; // Reset file input even if no files selected
      }
      return;
    }

    // Only photos and videos are supported; anything else is rejected up front
    // so it doesn't reach the backend as an unusable contentType.
    const acceptedFiles = filesToUpload.filter(file => file.type.startsWith('image/') || file.type.startsWith('video/'));
    const rejectedFiles = filesToUpload.filter(file => !(file.type.startsWith('image/') || file.type.startsWith('video/')));

    if (rejectedFiles.length) {
      const rejectedItems = rejectedFiles.map(file => ({
        id: Math.random().toString(36).substr(2, 9),
        uploadId: L.Util.stamp(file),
        name: file.name,
        status: 'FAILED',
        progress: 0,
        fileSize: file.size,
        contentType: file.type,
        totalChunks: 0,
        uploadedChunks: 0,
        taskId: null,
        error: messages["unsupportedFileType"](file.name),
        file: file,
      }));
      setUploadQueue(prev => [...rejectedItems, ...prev]);
    }

    if (!acceptedFiles.length) {
      if (fileInputRef.current) {
        fileInputRef.current.value = null;
      }
      return;
    }

    // Get albumId and spaceId from the dataset of the file input element
    const currentAlbumId = fileInputRef.current?.dataset.albumId || '';
    const currentSpaceId = fileInputRef.current?.dataset.spaceId || '';
    // Removed console.logs


    const newUploadItems = acceptedFiles.map(file => ({
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
      photoId: null,
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
        if (currentSpaceId) formData.append('spaceId', currentSpaceId); // Add spaceId to formData

        try {
          const res = await fetch('/api/upload/chunk', { method: 'POST', body: formData });
          if (!res.ok) {
            const errorText = await res.text();
            throw new Error(messages["serverRespondedWithError"](res.status, errorText));
          }
          const data = await res.json();

          if (data.status === 'completed') {
            setUploadQueue(prev => prev.map(item =>
                item.uploadId === uploadItem.uploadId ? { ...item, status: 'PROCESSING', taskId: data.task.id, photoId: data.task.photoId, uploadedChunks: item.totalChunks, progress: 100 } : item
            ));
            pollTaskStatus(uploadItem.uploadId, data.task.id);
          } else {
            setUploadQueue(prev => prev.map(item =>
                item.uploadId === uploadItem.uploadId ? { ...item, uploadedChunks: item.uploadedChunks + 1, progress: Math.round(((item.uploadedChunks + 1) / item.totalChunks) * 100) } : item
            ));
          }
        } catch (err) {
          console.error(messages["chunkUploadFailed"], err);
          setUploadQueue(prev => prev.map(item =>
              item.uploadId === uploadItem.uploadId ? { ...item, status: 'FAILED', error: messages["uploadFailed"](err.message) } : item
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
  }, [effectivePath, pollTaskStatus, refreshAndMaintainScroll, fileInputRef, messages]); // Add messages to dependencies

  // Original handleUpload for initial file selection
  const handleInitialUpload = useCallback((e) => {
    handleUploadFiles(Array.from(e.target.files || []));
  }, [handleUploadFiles]);

  const statusLabels = {
    UPLOADING: messages["uploading"],
    PROCESSING: messages["processing"],
    COMPLETED: messages["completed"],
    FAILED: messages["failed"],
    IGNORED: messages["alreadyImported"],
    TRASHED: messages["alreadyImportedAndTrashed"],
    PENDING: messages["pending"],
  };

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

  // Opens the newly uploaded asset in a lightweight preview popup (image/video only, no
  // metadata sidebar) without navigating away from the current page.
  const handleViewUploadedAsset = useCallback((item) => {
    if (!item.photoId) return;
    setPreviewPhotoId(item.photoId);
  }, []);

  return (
    <>
      {children({ handleUpload: handleInitialUpload, uploadQueue, setUploadQueue, fileInputRef })}
      {uploadQueue.length > 0 && (
          <div className="upload-queue">
            <div className="upload-queue-header"><span>{messages["uploads"](uploadQueue.length)}</span><X size={16} onClick={() => setUploadQueue([])} style={{cursor: 'pointer', opacity: 0.5}}/></div>
            <div className="upload-list">
              {uploadQueue.map(item => {
                const canPreview = item.photoId && item.status !== 'PENDING' && item.status !== 'FAILED';
                return (
                  <div key={item.id} className="upload-item">
                    <div className="upload-item-info">
                      {canPreview ? (
                        <button type="button" onClick={() => handleViewUploadedAsset(item)} className="upload-item-name upload-item-name-link" title={item.name}>{item.name}</button>
                      ) : (
                        <span className="upload-item-name" title={item.name}>{item.name}</span>
                      )}
                      <span className={`status-text ${item.status}`}>{statusLabels[item.status] || item.status}</span>
                    </div>
                    {item.error && (
                      <div className="upload-item-error">
                        {item.error}
                        {item.status === 'FAILED' && item.file && (
                          <button onClick={() => handleRetry(item.uploadId)} className="retry-button">{messages["retry"]}</button>
                        )}
                      </div>
                    )}
                    {['UPLOADING', 'PROCESSING', 'COMPLETED'].includes(item.status) && (
                        <div style={{ width: '100%', height: '4px', background: '#e0e0e5', borderRadius: '2px', marginTop: '6px', overflow: 'hidden' }}>
                          <div style={{ height: '100%', background: 'var(--primary-color)', width: `${item.progress || 0}%`, transition: 'width 0.2s ease' }} />
                        </div>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
      )}
      {previewPhotoId && (
        <UploadPreviewModal photoId={previewPhotoId} onClose={() => setPreviewPhotoId(null)} />
      )}
    </>
  );
}

export default UploadManager;