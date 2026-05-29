import React, { useState, useEffect, useCallback, useRef } from 'react'
import { Routes, Route, useNavigate, useLocation } from 'react-router-dom'
import 'leaflet/dist/leaflet.css'
import './App.css'

import L from 'leaflet'
import markerIcon from 'leaflet/dist/images/marker-icon.png'
import markerShadow from 'leaflet/dist/images/marker-shadow.png'
import Sidebar from './components/Sidebar'
import PhotoGrid from './components/PhotoGrid'
import ShareLinkPage from './components/ShareLinkPage';
import ShareLoginPage from './components/ShareLoginPage';
import UploadManager from './components/UploadManager';
import HeaderWrapper from './components/HeaderWrapper';
import AddToAlbumModal from './components/AddToAlbumModal';
import CreateShareLinkModal from './components/CreateShareLinkModal';
import AlbumDetail from './components/AlbumDetail';
import AlbumGridDisplay from './components/AlbumGridDisplay';
import DuplicatePhotoGrid from './components/DuplicatePhotoGrid';
import MapView from './components/MapView';
import LightboxWrapper from './components/LightboxWrapper';
import RemoveFromAlbumModal from './components/RemoveFromAlbumModal'; /* Import new modal */
import { usePhotoData } from './hooks/usePhotoData';
import { useAlbumManagement } from './hooks/useAlbumManagement';
import { useShareLinkManagement } from './hooks/useShareLinkManagement';
import { useDuplicateManagement } from './hooks/useDuplicateManagement';
import { useScrollRestoration } from './hooks/useScrollRestoration';
import { usePhotoActions } from './hooks/usePhotoActions';
import { usePhotoMetadata } from './hooks/usePhotoMetadata';
import { useMapAssets } from './hooks/useMapAssets';
import { useAppNavigationAndSelection } from './hooks/useAppNavigationAndSelection';
import { useInitialDataFetch } from './hooks/useInitialDataFetch';

let DefaultIcon = L.icon({ iconUrl: markerIcon, shadowUrl: markerShadow, iconSize: [25, 41], iconAnchor: [12, 41] })
L.Marker.prototype.options.icon = DefaultIcon

function App() {
  const navigate = useNavigate();
  const location = useLocation();
  const mainContentRef = useRef(null);

  const backgroundLocation = location.state?.backgroundLocation;
  const effectivePath = backgroundLocation?.pathname || location.pathname;
  const isLightboxOpen = location.pathname.startsWith('/asset/');

  /* Derive albumId from effectivePath */
  const currentAlbumId = effectivePath.startsWith('/albums/') ? effectivePath.split('/').pop() : null;

  /* Declare selectedIds and setSelectedIds at the top level */
  const [selectedIds, setSelectedIds] = useState([]);

  const { photos, setPhotos, page, setPage, hasMore, fetchPhotos } = usePhotoData(effectivePath, isLightboxOpen);
  const { saveScrollPosition } = useScrollRestoration(mainContentRef, isLightboxOpen, photos);

  const {
    duplicatesPage,
    setDuplicatesPage,
    currentDuplicateGroup,
    setCurrentDuplicateGroup,
    isLastDuplicateGroup,
    setIsLastDuplicateGroup,
    fetchDuplicates,
    handleFinishComparison,
  } = useDuplicateManagement();

  const {
    selectedPhoto,
    setSelectedPhoto,
    handlePhotoClick,
    handleCloseLightbox,
  } = useAppNavigationAndSelection(photos, currentDuplicateGroup, backgroundLocation, effectivePath, saveScrollPosition); /* Pass saveScrollPosition directly */

  const extMetadata = usePhotoMetadata(selectedPhoto);
  const mapAssets = useMapAssets(effectivePath);

  /* State to hold the refreshAndMaintainScroll function */
  const [refreshAndMaintainScroll, setRefreshAndMaintainScroll] = useState(() => () => {}); /* Initialize with a no-op function */

  /* Call useAlbumManagement first, passing the current (possibly no-op) refreshAndMaintainScroll */
  const {
    albums,
    setAlbums,
    showCreateAlbumModal,
    setShowCreateAlbumModal,
    showAddToAlbumModal,
    setShowAddToAlbumModal,
    targetAlbumIds,
    setTargetAlbumIds,
    initialTargetAlbumIds, /* New */
    newAlbumName,
    setNewAlbumName,
    newAlbumDescription,
    setNewAlbumDescription,
    albumCreationError,
    setAlbumCreationError,
    fetchAlbums,
    fetchAlbumsForPhotos, /* Get this for RemoveFromAlbumModal */
    handleCreateAlbum,
    handleAddPhotosToAlbums,
    handleRemovePhotosFromAlbum,
    handleDeleteAlbums,
    handleSetCoverPhoto,
    showRemoveFromAlbumModal,
    setShowRemoveFromAlbumModal,
    albumsPhotoBelongsTo,
    setAlbumsPhotoBelongsTo,
  } = useAlbumManagement(effectivePath, isLightboxOpen, selectedIds, setSelectedIds, refreshAndMaintainScroll);

  /* Define the actual refreshAndMaintainScroll function using useCallback */
  const actualRefreshAndMaintainScroll = useCallback(async () => {
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
    await fetchAlbums(); /* fetchAlbums is now available from useAlbumManagement */

    if (scrollPosition && mainContentRef.current) {
      requestAnimationFrame(() => {
        if(mainContentRef.current) {
          mainContentRef.current.scrollTop = scrollPosition;
        }
      });
    }
  }, [page, effectivePath, fetchAlbums, duplicatesPage, fetchDuplicates, setPhotos, mainContentRef]);

  /* Update the refreshAndMaintainScroll state once the actual function is defined */
  useEffect(() => {
    setRefreshAndMaintainScroll(() => actualRefreshAndMaintainScroll);
  }, [actualRefreshAndMaintainScroll]);


  const {
    handleMoveToGarbage,
    handleRestore,
    handlePermanentDelete,
    handleUpdateDate,
    handleEditPhoto,
  } = usePhotoActions(refreshAndMaintainScroll, setSelectedPhoto, handleCloseLightbox, selectedIds, setSelectedIds);


  const {
    showCreateShareLinkModal,
    setShowCreateShareLinkModal,
    albumIdForShareLink,
    setAlbumIdForShareLink,
    newShareLinkName,
    setNewShareLinkName,
    newShareLinkPassword,
    setNewShareLinkPassword,
    newShareLinkExpiresInDays,
    setNewShareLinkExpiresInDays,
    shareLinkCreationError,
    setShareLinkCreationError,
    handleCreateShareLink,
    handleShowCreateShareLinkModal,
  } = useShareLinkManagement();

  useInitialDataFetch(
    effectivePath,
    isLightboxOpen,
    setSelectedIds,
    setDuplicatesPage,
    fetchDuplicates
  );

  /* Function to handle showing the AddToAlbumModal from Lightbox */
  const handleShowAddToAlbumModalForLightbox = useCallback((photoId) => {
    setSelectedIds([photoId]); /* Select only the current photo */
    setShowAddToAlbumModal(true);
  }, [setSelectedIds, setShowAddToAlbumModal]);

  /* New function to handle showing the RemoveFromAlbumModal from Lightbox */
  const handleShowRemoveFromAlbumModalForLightbox = useCallback(async (photoId) => {
    if (photoId) {
      const albums = await fetchAlbumsForPhotos([photoId]);
      setAlbumsPhotoBelongsTo(albums);
      setSelectedPhoto(p => ({ ...p, id: photoId })); /* Ensure selectedPhoto is set for the modal */
      setShowRemoveFromAlbumModal(true);
    }
  }, [fetchAlbumsForPhotos, setAlbumsPhotoBelongsTo, setSelectedPhoto, setShowRemoveFromAlbumModal]);


  return (
      <div className="app-container">
        <Sidebar pathname={backgroundLocation?.pathname || location.pathname} />

        <main className="main-content" ref={mainContentRef}>
          <UploadManager refreshAndMaintainScroll={refreshAndMaintainScroll} effectivePath={effectivePath}>
            {({ handleUpload }) => (
                <HeaderWrapper
                    selectedIds={selectedIds}
                    setSelectedIds={setSelectedIds}
                    handleRestore={handleRestore}
                    handlePermanentDelete={handlePermanentDelete}
                    handleRemovePhotosFromAlbum={handleRemovePhotosFromAlbum}
                    handleMoveToGarbage={handleMoveToGarbage}
                    setShowAddToAlbumModal={setShowAddToAlbumModal}
                    setShowCreateShareLinkModal={handleShowCreateShareLinkModal}
                    handleUpload={handleUpload}
                    handleSetCoverPhoto={handleSetCoverPhoto}
                    handleDeleteAlbums={handleDeleteAlbums}
                    navigate={navigate}
                    duplicatesPage={duplicatesPage}
                    fetchDuplicates={fetchDuplicates}
                    isLastDuplicateGroup={isLastDuplicateGroup}
                    photos={photos}
                    currentDuplicateGroup={currentDuplicateGroup}
                    backgroundLocation={backgroundLocation}
                    albumId={currentAlbumId}
                />
            )}
          </UploadManager>

          <Routes location={backgroundLocation || location}>
            <Route path="/" element={
              <PhotoGrid photos={photos} hasMore={hasMore} setPage={setPage} selectedIds={selectedIds} setSelectedIds={setSelectedIds} setSelectedPhoto={handlePhotoClick} isLightboxOpen={isLightboxOpen} />
            } />
            <Route path="/garbage" element={
              <PhotoGrid photos={photos} hasMore={hasMore} setPage={setPage} selectedIds={selectedIds} setSelectedIds={setSelectedIds} setSelectedPhoto={handlePhotoClick} isLightboxOpen={isLightboxOpen} />
            } />
            <Route path="/map" element={
              isLightboxOpen ? null : (
                  <MapView mapAssets={mapAssets} handlePhotoClick={handlePhotoClick} />
              )
            } />
            <Route path="/duplicates/*" element={
              isLightboxOpen ? null : (
                <DuplicatePhotoGrid
                  currentDuplicateGroup={currentDuplicateGroup}
                  selectedIds={selectedIds}
                  setSelectedIds={setSelectedIds}
                  handlePhotoClick={handlePhotoClick}
                  duplicatesPage={duplicatesPage}
                  fetchDuplicates={fetchDuplicates}
                  isLastDuplicateGroup={isLastDuplicateGroup}
                  handleFinishComparison={handleFinishComparison}
                />
              )
            } />
            <Route path="/albums" element={
              <AlbumGridDisplay
                albums={albums}
                selectedIds={selectedIds}
                setSelectedIds={setSelectedIds}
                newAlbumName={newAlbumName}
                setNewAlbumName={setNewAlbumName}
                newAlbumDescription={newAlbumDescription}
                setNewAlbumDescription={setNewAlbumDescription}
                albumCreationError={albumCreationError}
                setAlbumCreationError={setAlbumCreationError}
                handleCreateAlbum={handleCreateAlbum}
                showCreateAlbumModal={showCreateAlbumModal}
                setShowCreateAlbumModal={setShowCreateAlbumModal}
              />
            } />
            <Route path="/albums/:albumId" element={
              <AlbumDetail
                albums={albums}
                photos={photos}
                hasMore={hasMore}
                setPage={setPage}
                selectedIds={selectedIds}
                setSelectedIds={setSelectedIds}
                handlePhotoClick={handlePhotoClick}
                isLightboxOpen={isLightboxOpen}
              />
            } />
            <Route path="/share-links" element={
              <div className="content-body" style={{ textAlign: 'center', paddingTop: '40px' }}>
                <h2>Manage Share Links</h2>
                <p>This is where you will manage your share links.</p>
              </div>
            } />
            <Route path="/share/:name" element={<ShareLinkPage />} />
            <Route path="/share/:name/login" element={<ShareLoginPage />} />
          </Routes>
        </main>

        <LightboxWrapper
            isLightboxOpen={isLightboxOpen}
            selectedPhoto={selectedPhoto}
            onClose={handleCloseLightbox}
            extMetadata={extMetadata}
            backgroundLocation={backgroundLocation}
            handleMoveToGarbage={handleMoveToGarbage}
            handleRestore={handleRestore}
            handlePermanentDelete={handlePermanentDelete}
            photos={photos}
            currentDuplicateGroup={currentDuplicateGroup}
            onNavigate={handlePhotoClick}
            onUpdateDate={handleUpdateDate} /* Corrected from onUpdateDate */
            onEditPhoto={handleEditPhoto}
            onShowAddToAlbumModal={handleShowAddToAlbumModalForLightbox}
            onShowRemoveFromAlbumModal={handleShowRemoveFromAlbumModalForLightbox}
        />

        <AddToAlbumModal
          show={showAddToAlbumModal}
          onClose={() => setShowAddToAlbumModal(false)}
          albums={albums}
          selectedIds={selectedIds}
          targetAlbumIds={targetAlbumIds}
          setTargetAlbumIds={setTargetAlbumIds}
          initialTargetAlbumIds={initialTargetAlbumIds}
          handleAddPhotosToAlbums={handleAddPhotosToAlbums}
        />

        <RemoveFromAlbumModal /* New modal */
          show={showRemoveFromAlbumModal}
          onClose={() => setShowRemoveFromAlbumModal(false)}
          photoId={selectedPhoto?.id} /* Pass the ID of the photo in the lightbox */
          albumsPhotoBelongsTo={albumsPhotoBelongsTo}
          handleRemovePhotosFromAlbum={handleRemovePhotosFromAlbum}
          fetchAlbumsForPhotos={fetchAlbumsForPhotos}
        />

        <CreateShareLinkModal
          show={showCreateShareLinkModal}
          onClose={() => { setShowCreateShareLinkModal(false); setAlbumIdForShareLink(null); }}
          albumIdForShareLink={albumIdForShareLink}
          albums={albums}
          newShareLinkName={newShareLinkName}
          setNewShareLinkName={setNewShareLinkName}
          newShareLinkPassword={newShareLinkPassword}
          setNewShareLinkPassword={setNewShareLinkPassword}
          newShareLinkExpiresInDays={newShareLinkExpiresInDays}
          setNewShareLinkExpiresInDays={setNewShareLinkExpiresInDays}
          shareLinkCreationError={shareLinkCreationError}
          handleCreateShareLink={handleCreateShareLink}
        />
      </div>
  )
}

export default App