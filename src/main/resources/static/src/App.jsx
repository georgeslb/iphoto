import React, { useState, useEffect, useCallback, useRef } from 'react'
import { Routes, Route, useNavigate, useLocation } from 'react-router-dom'
import 'leaflet/dist/leaflet.css'
import './App.css'

/* Removed L, markerIcon, markerShadow imports as they are not directly used in App.jsx */
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
import { usePhotoMetadata } from './hooks/usePhotoMetadata';
import { useMapAssets } from './hooks/useMapAssets';
import { useAppNavigationAndSelection } from './hooks/useAppNavigationAndSelection';
import { useInitialDataFetch } from './hooks/useInitialDataFetch';

/* Removed DefaultIcon definition as it's not used */

function App() {
  const navigate = useNavigate();
  const location = useLocation();
  const mainContentRef = useRef(null);
  const fileInputRef = useRef(null); // Declare fileInputRef here

  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [userRole, setUserRole] = useState(null);
  const [loadingAuth, setLoadingAuth] = useState(true);

  const backgroundLocation = location.state?.backgroundLocation;
  const effectivePath = backgroundLocation?.pathname || location.pathname;
  const isLightboxOpen = location.pathname.startsWith('/asset/') || /^\/albums\/[^/]+\/asset\//.test(location.pathname) || /^\/spaces\/[^/]+\/asset\//.test(location.pathname); // Updated for spaces

  /* Derive albumId/spaceId from effectivePath */
  const currentAlbumId = effectivePath.startsWith('/albums/') ? effectivePath.split('/')[2] : null;
  const currentSpaceId = effectivePath.startsWith('/spaces/') ? effectivePath.split('/')[2] : null;

  // Removed console.logs


  /* Declare selectedIds and setSelectedIds at the top level */
  const [selectedIds, setSelectedIds] = useState([]);

  const checkAuthStatus = useCallback(async () => {
    try {
      const response = await fetch('/api/user/me');
      if (response.ok) {
        const userData = await response.json();
        setIsAuthenticated(true);
        setUserRole(userData.role);
        if (location.pathname === '/login') {
          navigate('/'); // Redirect from login page if already authenticated
        }
      } else {
        setIsAuthenticated(false);
        setUserRole(null);
        if (location.pathname !== '/login' && !location.pathname.startsWith('/share')) {
          navigate('/login'); // Redirect to login if not authenticated and not on a share page
        }
      }
    } catch (error) {
      console.error('Error checking authentication status:', error);
      setIsAuthenticated(false);
      setUserRole(null);
      if (location.pathname !== '/login' && !location.pathname.startsWith('/share')) {
        navigate('/login');
      }
    } finally {
      setLoadingAuth(false);
    }
  }, [navigate, location.pathname]);

  useEffect(() => {
    checkAuthStatus();
  }, [checkAuthStatus]);

  const handleLogout = async () => {
    try {
      await fetch('/api/logout', { method: 'POST' });
      setIsAuthenticated(false);
      setUserRole(null);
      navigate('/login');
    } catch (error) {
      console.error('Error logging out:', error);
    }
  };


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

  // --- Space Management ---
  const [spaces, setSpaces] = useState([]);
  const [newSpaceName, setNewSpaceName] = useState('');
  const [newSpaceDescription, setNewSpaceDescription] = useState('');
  const [spaceCreationError, setSpaceCreationError] = useState('');
  const [showCreateSpaceModal, setShowCreateSpaceModal] = useState(false);
  const [selectedSpaceIds, setSelectedSpaceIds] = useState([]); // For deleting multiple spaces
  const [currentSpaceDetails, setCurrentSpaceDetails] = useState(null); // New state for current space details

  const fetchSpaces = useCallback(async () => {
    try {
      const response = await fetch('/api/spaces');
      if (response.ok) {
        const data = await response.json();
        setSpaces(data);
      } else {
        console.error('Failed to fetch spaces');
      }
    } catch (error) {
      console.error('Error fetching spaces:', error);
    }
  }, []);

  useEffect(() => {
    fetchSpaces();
  }, [fetchSpaces]);

  // Effect to set currentSpaceDetails when currentSpaceId or spaces change
  useEffect(() => {
    if (currentSpaceId && spaces.length > 0) {
      const foundSpace = spaces.find(space => space.id === currentSpaceId);
      setCurrentSpaceDetails(foundSpace || null);
    } else {
      setCurrentSpaceDetails(null);
    }
  }, [currentSpaceId, spaces]);


  const handleCreateSpace = useCallback(async () => {
    setSpaceCreationError('');
    try {
      const response = await fetch('/api/spaces', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: newSpaceName, description: newSpaceDescription }),
      });
      if (response.ok) {
        setNewSpaceName('');
        setNewSpaceDescription('');
        setShowCreateSpaceModal(false);
        fetchSpaces();
      } else {
        const errorData = await response.json();
        setSpaceCreationError(errorData.message || 'Failed to create space.');
      }
    } catch (error) {
      console.error('Error creating space:', error);
      setSpaceCreationError('An unexpected error occurred.');
    }
  }, [newSpaceName, newSpaceDescription, fetchSpaces]);

  const handleDeleteSpaces = useCallback(async (idsToDelete) => {
    if (!window.confirm(`Are you sure you want to delete ${idsToDelete.length} space(s)?`)) {
      return;
    }
    try {
      for (const id of idsToDelete) {
        await fetch(`/api/spaces/${id}`, { method: 'DELETE' });
      }
      setSelectedSpaceIds([]); // Clear selection
      fetchSpaces(); // Refresh list
    } catch (error) {
      console.error('Error deleting spaces:', error);
    }
  }, [fetchSpaces]);
  // --- End Space Management ---


  /* Define the actual refreshAndMaintainScroll function using useCallback */
  const actualRefreshAndMaintainScroll = useCallback(async () => {
    const scrollPosition = mainContentRef.current?.scrollTop;

    if (effectivePath.startsWith('/duplicates')) {
      await fetchDuplicates(duplicatesPage);
      return;
    }

    const type = effectivePath === '/garbage' ? 'garbage' : (effectivePath.startsWith('/albums/') ? 'album' : (effectivePath.startsWith('/spaces/') ? 'space' : 'library'));
    const albumId = type === 'album' ? effectivePath.split('/')[2] : null;
    const spaceId = type === 'space' ? effectivePath.split('/')[2] : null;

    // Use the fetchPhotos from usePhotoData to refresh the current view
    // This will reset the photos and fetch the first page (page 0)
    await fetchPhotos(0, true, type, albumId, spaceId);

    await fetchAlbums(); /* fetchAlbums is now available from useAlbumManagement */
    await fetchSpaces(); // Fetch spaces as well

    if (scrollPosition && mainContentRef.current) {
      requestAnimationFrame(() => {
        if(mainContentRef.current) {
          mainContentRef.current.scrollTop = scrollPosition;
        }
      });
    }
  }, [effectivePath, fetchAlbums, fetchSpaces, duplicatesPage, fetchDuplicates, fetchPhotos, mainContentRef]); // Added fetchPhotos to dependencies

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

  if (loadingAuth) {
    return <div>Loading authentication...</div>;
  }

  return (
      <div className="app-container">
        {isAuthenticated && (
          <Sidebar
              pathname={backgroundLocation?.pathname || location.pathname}
              isAuthenticated={isAuthenticated}
              userRole={userRole}
              handleLogout={handleLogout}
          />
        )}

        <main className="main-content" ref={mainContentRef}>
          {isAuthenticated && (
            <UploadManager refreshAndMaintainScroll={refreshAndMaintainScroll} effectivePath={effectivePath} fileInputRef={fileInputRef}>
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
                      spaceId={currentSpaceId} // Pass currentSpaceId
                      allowCopyToMain={currentSpaceDetails?.allowCopyToMain || false} // Pass allowCopyToMain
                      refreshAndMaintainScroll={refreshAndMaintainScroll} // Pass refresh function
                      fileInputRef={fileInputRef} // Pass fileInputRef to HeaderWrapper
                  />
              )}
            </UploadManager>
          )}

          <Routes location={backgroundLocation || location}>
            <Route path="/login" element={<Login />} />
            {isAuthenticated ? (
                <>
                  <Route path="/" element={
                    <PhotoGrid photos={photos} hasMore={hasMore} setPage={setPage} selectedIds={selectedIds} setSelectedIds={setSelectedIds} setSelectedPhoto={handlePhotoClick} isLightboxOpen={isLightboxOpen} />
                  } />
                  <Route path="/garbage" element={
                    <PhotoGrid photos={photos} hasMore={hasMore} setPage={setPage} selectedIds={selectedIds} setSelectedIds={setSelectedIds} setSelectedPhoto={handlePhotoClick} isLightboxOpen={isLightboxOpen} />
                  } />
                  <Route path="/asset/:photoId" element={
                    <PhotoGrid photos={photos} hasMore={hasMore} setPage={setPage} selectedIds={selectedIds} setSelectedIds={setSelectedIds} setSelectedPhoto={handlePhotoClick} isLightboxOpen={isLightboxOpen} />
                  } />
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
                  <Route path="/albums/:albumId/asset/:photoId" element={
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
                  {/* New Space Routes */}
                  <Route path="/spaces" element={
                    <SpaceGridDisplay
                        spaces={spaces}
                        selectedIds={selectedSpaceIds}
                        setSelectedIds={setSelectedSpaceIds}
                        newSpaceName={newSpaceName}
                        setNewSpaceName={setNewSpaceName}
                        newSpaceDescription={newSpaceDescription}
                        setNewSpaceDescription={setNewSpaceDescription}
                        spaceCreationError={spaceCreationError}
                        setSpaceCreationError={setSpaceCreationError}
                        handleCreateSpace={handleCreateSpace}
                        showCreateSpaceModal={showCreateSpaceModal}
                        setShowCreateSpaceModal={setShowCreateSpaceModal}
                        fetchSpaces={fetchSpaces}
                        handleDeleteSpaces={handleDeleteSpaces}
                    />
                  } />
                  <Route path="/spaces/:spaceId" element={
                    <SpaceDetail
                        spaces={spaces}
                        photos={photos}
                        hasMore={hasMore}
                        setPage={setPage}
                        selectedIds={selectedIds}
                        setSelectedIds={setSelectedIds}
                        handlePhotoClick={handlePhotoClick}
                        isLightboxOpen={isLightboxOpen}
                    />
                  } />
                  <Route path="/spaces/:spaceId/asset/:photoId" element={
                    <SpaceDetail
                        spaces={spaces}
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
            handleRestore={handleRestore}
            handlePermanentDelete={handlePermanentDelete}
            photos={photos}
            currentDuplicateGroup={currentDuplicateGroup}
            onNavigate={handlePhotoClick}
            onUpdateDate={handleUpdateDate}
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