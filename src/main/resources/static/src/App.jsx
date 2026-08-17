import React, { useState, useEffect, useCallback, useRef } from 'react'
import { Routes, Route, useNavigate, useLocation } from 'react-router-dom'
import 'leaflet/dist/leaflet.css'
import './App.css'
import { useLocale } from './LocaleContext.jsx'; // Import useLocale

/* Removed L, markerIcon, markerShadow imports as they are not directly used in App.jsx */
import Sidebar from './components/Sidebar'
import PhotoGrid from './components/PhotoGrid'
import ShareLinkPage from './components/ShareLinkPage';
import ShareLoginPage from './components/ShareLoginPage';
import UploadManager from './components/UploadManager';
import HeaderWrapper from './components/HeaderWrapper';
import AddToAlbumModal from './components/AddToAlbumModal';
import CreateShareLinkModal from './components/CreateShareLinkModal';
import CreateAlbumModal from './components/CreateAlbumModal';
import CreateSpaceModal from './components/CreateSpaceModal';
import AlbumDetail from './components/AlbumDetail';
import AlbumGridDisplay from './components/AlbumGridDisplay';
import DuplicatePhotoGrid from './components/DuplicatePhotoGrid';
import MapView from './components/MapView';
import LightboxWrapper from './components/LightboxWrapper';
import RemoveFromAlbumModal from './components/RemoveFromAlbumModal'; /* Import new modal */
import Persons from './components/Persons';
import Login from './components/Login'; // Import Login component
import UserManagement from './components/UserManagement'; // Import UserManagement component
import SpaceGridDisplay from './components/SpaceGridDisplay'; // Import SpaceGridDisplay
import SpaceDetail from './components/SpaceDetail'; // Import SpaceDetail
import { usePhotoData } from './hooks/usePhotoData';
import { useAlbumManagement } from './hooks/useAlbumManagement';
import { useShareLinkManagement } from './hooks/useShareLinkManagement';
import { useDuplicateManagement } from './hooks/useDuplicateManagement';
import { useScrollRestoration } from './hooks/useScrollRestoration';
import { usePhotoActions } from './hooks/usePhotoActions';
import { useSpaceActions } from './hooks/useSpaceActions';
import { useSpaceManagement } from './hooks/useSpaceManagement';
import { usePhotoMetadata } from './hooks/usePhotoMetadata';
import { useMapAssets } from './hooks/useMapAssets';
import { useAppNavigationAndSelection } from './hooks/useAppNavigationAndSelection';
import { useInitialDataFetch } from './hooks/useInitialDataFetch';
import { useMenuSelection } from './hooks/useMenuSelection.js';
import { useAuth } from './hooks/useAuth';
import { useRouteContext } from './hooks/useRouteContext';

/* Removed DefaultIcon definition as it's not used */

function App() {
  const { messages } = useLocale(); // Use the useLocale hook
  const navigate = useNavigate();
  const location = useLocation();
  const mainContentRef = useRef(null);
  const fileInputRef = useRef(null); // Declare fileInputRef here

  const { isAuthenticated, userRole, loadingAuth, handleLogout } = useAuth(navigate, location);

  const {
    backgroundLocation,
    effectivePath,
    isLightboxOpen,
    currentAlbumId,
    currentSpaceId,
    fromGarbage,
    selectedMenu,
  } = useRouteContext(location);


  /* Declare selectedIds and setSelectedIds at the top level */
  const [selectedIds, setSelectedIds] = useState([]);
  const [selectedAlbumIds, setSelectedAlbumIds] = useState([]);
  const [selectedSpaceIds, setSelectedSpaceIds] = useState([]); // For deleting multiple spaces
  const { photos, setPage, hasMore, fetchPhotos } = usePhotoData(effectivePath, isLightboxOpen);

  const { saveScrollPosition } = useScrollRestoration(mainContentRef, isLightboxOpen, photos);

  const {
    duplicatesPage,
    setDuplicatesPage,
    currentDuplicateGroup,
    isLastDuplicateGroup,
    fetchDuplicates,
    handleFinishComparison,
  } = useDuplicateManagement();

  const {
    selectedPhoto,
    setSelectedPhoto,
    handlePhotoClick,
    handleCloseLightbox,
  } = useAppNavigationAndSelection(photos, currentDuplicateGroup, backgroundLocation, effectivePath, saveScrollPosition);

  let [photoSelection, setPhotoSelection] = useState({
    menuSelection: {
      canUpload: false,
      canCreateAlbum: false,
      canDeleteAlbum: false,
      canCreateSpace: false,
      canDeleteSpace: false,
      canPermanentlyDeleteSpace: false,
      canRestoreSpace: false,
      canAddAAShareLink: false,
    },
    canBeDeleted: false, canBePermanentlyDeleted: false, canBeMovedToMain: false,
    canBeMovedToAlbum: false, canBeRemovedFromAlbum: false, canBeModified: false,
    canBeSetAsCover: false, canBeRestored: false
  });

  useMenuSelection(effectivePath, currentSpaceId, currentAlbumId, isLightboxOpen, selectedAlbumIds, selectedSpaceIds, selectedIds, selectedPhoto, setPhotoSelection);

  useEffect(() => {
    // Reset both selections when the path changes
    setSelectedIds([]);
    setSelectedAlbumIds([]);
    setSelectedSpaceIds([]);
  }, [location.pathname]);

  const extMetadata = usePhotoMetadata(selectedPhoto);
  const mapAssets = useMapAssets(effectivePath);

  /* State to hold the refreshAndMaintainScroll function */
  const [refreshAndMaintainScroll, setRefreshAndMaintainScroll] = useState(() => () => {}); /* Initialize with a no-op function */

  /* Call useAlbumManagement first, passing the current (possibly no-op) refreshAndMaintainScroll */
  const {
    albums,
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

  const {
    spaces,
    garbageSpaces,
    newSpaceName,
    setNewSpaceName,
    newSpaceDescription,
    setNewSpaceDescription,
    spaceCreationError,
    showCreateSpaceModal,
    setShowCreateSpaceModal,
    fetchSpaces,
    fetchGarbageSpaces,
    handleCreateSpace,
    handleDeleteSpaces,
  } = useSpaceManagement(selectedMenu, setSelectedSpaceIds);


  /* Define the actual refreshAndMaintainScroll function using useCallback */
  const actualRefreshAndMaintainScroll = useCallback(async () => {
    const scrollPosition = mainContentRef.current?.scrollTop;

    if (effectivePath.startsWith('/duplicates')) {
      await fetchDuplicates(duplicatesPage);
      return;
    }



    // Use the fetchPhotos from usePhotoData to refresh the current view
    // This will reset the photos and fetch the first page (page 0)
    await fetchPhotos(0, true, selectedMenu, currentAlbumId, currentSpaceId);

    await fetchAlbums(); /* fetchAlbums is now available from useAlbumManagement */
    await fetchSpaces(); // Fetch spaces as well
    if (selectedMenu === 'garbage') {
      await fetchGarbageSpaces();
    }

    if (scrollPosition && mainContentRef.current) {
      requestAnimationFrame(() => {
        if(mainContentRef.current) {
          mainContentRef.current.scrollTop = scrollPosition;
        }
      });
    }
  }, [effectivePath, fetchAlbums, fetchSpaces, fetchGarbageSpaces, duplicatesPage, fetchDuplicates, fetchPhotos, mainContentRef]); // Added fetchPhotos to dependencies

  /* Update the refreshAndMaintainScroll state once the actual function is defined */
  useEffect(() => {
    setRefreshAndMaintainScroll(() => actualRefreshAndMaintainScroll);
  }, [actualRefreshAndMaintainScroll]);


  const {
    handleMoveToGarbage,
    handleMoveToMain,
    handleRestore,
    handlePermanentDelete,
    handleUpdateDate,
    handleEditPhoto,
  } = usePhotoActions(refreshAndMaintainScroll, setSelectedPhoto, handlePhotoClick, selectedIds, setSelectedIds);

  const {
    handleRestoreSpaces,
    handlePermanentDeleteSpaces,
  } = useSpaceActions(refreshAndMaintainScroll, setSelectedSpaceIds);


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

  if (loadingAuth) {
    return <div>{messages["loadingAuthentication"]}</div>;
  }

  const libraryPhotoGrid = (
    <PhotoGrid photos={photos} type={selectedMenu} hasMore={hasMore} setPage={setPage} selectedIds={selectedIds} setSelectedIds={setSelectedIds} setSelectedPhoto={handlePhotoClick} isLightboxOpen={isLightboxOpen} />
  );

  const albumDetailElement = (
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
  );

  return (
      <div className="app-container">
        {isAuthenticated && (
          <Sidebar
              pathname={backgroundLocation?.pathname || location.pathname}
              isAuthenticated={isAuthenticated}
              userRole={userRole}
              handleLogout={handleLogout}
              fromGarbage={fromGarbage}
          />
        )}

        <main className="main-content" ref={mainContentRef}>
          {isAuthenticated && (
            <UploadManager refreshAndMaintainScroll={refreshAndMaintainScroll} effectivePath={effectivePath} fileInputRef={fileInputRef}>
              {({ handleUpload }) => (
                  <HeaderWrapper
                      selectedIds={selectedIds}
                      setSelectedIds={setSelectedIds}
                      selectedSpaceIds={selectedSpaceIds}
                      selectedAlbumIds={selectedAlbumIds}
                      setSelectedSpaceIds={setSelectedSpaceIds}
                      setSelectedAlbumIds={setSelectedAlbumIds}
                      menuSpaceId={currentSpaceId}
                      menuAlbumId={currentAlbumId}
                      handleRestore={handleRestore}
                      handlePermanentDelete={handlePermanentDelete}
                      handleRestoreSpaces={handleRestoreSpaces}
                      handlePermanentDeleteSpaces={handlePermanentDeleteSpaces}
                      handleRemovePhotosFromAlbum={handleRemovePhotosFromAlbum}
                      handleMoveToGarbage={handleMoveToGarbage}
                      setShowAddToAlbumModal={setShowAddToAlbumModal}
                      setShowCreateShareLinkModal={handleShowCreateShareLinkModal}
                      handleUpload={handleUpload}
                      handleSetCoverPhoto={handleSetCoverPhoto}
                      handleDeleteAlbums={handleDeleteAlbums}
                      handleDeleteSpaces={handleDeleteSpaces}
                      setShowCreateAlbumModal={setShowCreateAlbumModal}
                      setShowCreateSpaceModal={setShowCreateSpaceModal}
                      navigate={navigate}
                      duplicatesPage={duplicatesPage}
                      fetchDuplicates={fetchDuplicates}
                      isLastDuplicateGroup={isLastDuplicateGroup}
                      backgroundLocation={backgroundLocation}
                      photoSelection={photoSelection}
                      fileInputRef={fileInputRef}
                      fromGarbage={fromGarbage}
                      refreshAndMaintainScroll={refreshAndMaintainScroll}
                  />
              )}
            </UploadManager>
          )}

          <Routes location={backgroundLocation || location}>
            <Route path="/login" element={<Login />} />
            {isAuthenticated ? (
                <>
                  <Route path="/" element={libraryPhotoGrid} />
                  <Route path="/garbage" element={
                    <>
                      <h2 style={{padding: '20px 20px 0 20px'}}>{messages["spaces"] || 'Spaces'}</h2>
                      <SpaceGridDisplay
                          spaces={garbageSpaces}
                          selectedSpaceIds={selectedSpaceIds}
                          setSelectedSpaceIds={setSelectedSpaceIds}
                          setSelectedIds={setSelectedIds}
                          fetchSpaces={fetchGarbageSpaces}
                          selectionMenu={selectedMenu}
                      />
                      <h2 style={{padding: '20px 20px 0 20px'}}>{messages["photos"] || 'Photos'}</h2>
                      {libraryPhotoGrid}
                    </>
                  } />
                  <Route path="/asset/:photoId" element={libraryPhotoGrid} />
                  <Route path="/map" element={
                    isLightboxOpen ? null : (
                        <MapView mapAssets={mapAssets} handlePhotoClick={handlePhotoClick} />
                    )
                  } />
                  <Route path="/persons" element={<Persons />} />
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
                        selectedIds={selectedAlbumIds}
                        setSelectedIds={setSelectedAlbumIds}
                    />
                  } />
                  <Route path="/albums/:albumId" element={albumDetailElement} />
                  <Route path="/albums/:albumId/asset/:photoId" element={albumDetailElement} />
                  {/* New Space Routes */}
                  <Route path="/spaces" element={
                    <SpaceGridDisplay
                        spaces={spaces}
                        selectedSpaceIds={selectedSpaceIds}
                        setSelectedSpaceIds={setSelectedSpaceIds}
                        fetchSpaces={fetchSpaces}
                    />
                  } />
                  <Route path="/spaces/:spaceId" element={
                    <SpaceDetail
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
                      <h2>{messages["manageShareLinks"]}</h2>
                      <p>{messages["manageShareLinksDescription"]}</p>
                    </div>
                  } />
                  {userRole === 'ADMIN' && (
                      <Route path="/admin/users" element={<UserManagement />} />
                  )}
                </>
            ) : (
                // Redirect all non-share, non-login paths to login if not authenticated
                <Route path="*" element={<Login />} />
            )}
            {/* Share routes are always accessible */}
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
            handleMoveToMain={handleMoveToMain}
            handleRestore={handleRestore}
            handlePermanentDelete={handlePermanentDelete}
            photos={photos}
            currentDuplicateGroup={currentDuplicateGroup}
            onNavigate={handlePhotoClick}
            onUpdateDate={handleUpdateDate}
            onEditPhoto={handleEditPhoto}
            onShowAddToAlbumModal={handleShowAddToAlbumModalForLightbox}
            onShowRemoveFromAlbumModal={handleShowRemoveFromAlbumModalForLightbox}
            photoSelection={photoSelection}
        />

        <CreateAlbumModal
            show={showCreateAlbumModal}
            onClose={() => setShowCreateAlbumModal(false)}
            newAlbumName={newAlbumName}
            setNewAlbumName={setNewAlbumName}
            newAlbumDescription={newAlbumDescription}
            setNewAlbumDescription={setNewAlbumDescription}
            albumCreationError={albumCreationError}
            handleCreateAlbum={handleCreateAlbum}
        />

        <CreateSpaceModal
            show={showCreateSpaceModal}
            onClose={() => setShowCreateSpaceModal(false)}
            newSpaceName={newSpaceName}
            setNewSpaceName={setNewSpaceName}
            newSpaceDescription={newSpaceDescription}
            setNewSpaceDescription={setNewSpaceDescription}
            spaceCreationError={spaceCreationError}
            handleCreateSpace={handleCreateSpace}
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