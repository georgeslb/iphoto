import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import { Routes, Route, useNavigate, useLocation } from 'react-router-dom'
import 'leaflet/dist/leaflet.css'
import './App.css'
import { useLocale } from './LocaleContext.jsx'; // Import useLocale

/* Removed L, markerIcon, markerShadow imports as they are not directly used in App.jsx */
import Sidebar from './components/Sidebar'
import PhotoGrid from './components/PhotoGrid'
import SharedAlbumView from './components/SharedAlbumView';
import ShareLinksManagement from './components/ShareLinksManagement';
import UploadManager from './components/UploadManager';
import HeaderWrapper from './components/HeaderWrapper';
import AddToAlbumModal from './components/AddToAlbumModal';
import EditDateModal from './components/EditDateModal';
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
import PersonDetail from './components/PersonDetail';
import Login from './components/Login'; // Import Login component
import UserManagement from './components/UserManagement'; // Import UserManagement component
import ImmichImportManagement from './components/ImmichImportManagement';
import TaskManagement from './components/TaskManagement';
import SpaceGridDisplay from './components/SpaceGridDisplay'; // Import SpaceGridDisplay
import SpaceDetail from './components/SpaceDetail'; // Import SpaceDetail
import MemoriesBar from './components/MemoriesBar';
import RecentlyAddedSection from './components/RecentlyAddedSection';
import PersonMergeSuggestionBanner from './components/PersonMergeSuggestionBanner';
import { useDayIndexedPhotos } from './hooks/useDayIndexedPhotos';
import { removeFromPhotosByDay } from './utils/photoListUpdates';
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

  const { isAuthenticated, userRole, duplicationRegrouping, setDuplicationRegrouping, loadingAuth, handleLogout, checkAuthStatus } = useAuth(navigate, location);

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
  // Photo/video type filter for Library/Space/Album/Garbage (Header's MediaTypeFilter control) -
  // 'all' | 'photo' | 'video'. Lives here, not in Header, since useDayIndexedPhotos (also here)
  // needs it to build its request URLs.
  const [mediaTypeFilter, setMediaTypeFilter] = useState('all');

  // Day-index browsing (replaces the old month-timeline/jumpToMonth cursor system, and now the
  // only technology every photo-listing view - Library/Album/Space/Garbage/Person - uses):
  // fetches every day up front, then lazily fetches each day's photos as its section scrolls
  // into view.
  // Gated on isAuthenticated so the days-fetch waits for the auth check to confirm the session
  // instead of racing it (see useDayIndexedPhotos.js's `enabled` param doc).
  const dayIndexed = useDayIndexedPhotos(effectivePath, null, isAuthenticated, mediaTypeFilter);

  // Flattened "reading order" view of the day-indexed data, in the same shape PhotoGrid.jsx's own
  // flatSelectionPhotos uses - needed anywhere something wants "the current view's whole photo
  // collection" (Lightbox prev/next navigation, scroll restoration, direct-asset-URL lookup), as
  // opposed to PhotoGrid's own rendering, which reads the day-bucketed shape directly.
  const effectivePhotos = useMemo(
    () => dayIndexed.days.flatMap(d => dayIndexed.photosByDay[d.day] || []),
    [dayIndexed.days, dayIndexed.photosByDay]
  );

  // MemoriesBar's click target: scroll to that photo's day section, same instant-scroll-plus-
  // eager-load behavior as PhotoGrid.jsx's own seekToDay (TimelineScrubber) - not the Lightbox.
  // Queries the DOM directly (data-day-group, rendered by PhotoGrid for every day up front)
  // rather than threading a ref down from PhotoGrid, since MemoriesBar is a sibling of it, not a
  // child - only one Library PhotoGrid is ever on screen at a time, so this is unambiguous.
  const seekToMemoryDay = useCallback((day) => {
    const el = document.querySelector(`[data-day-group="${day}"]`);
    if (el) el.scrollIntoView({ block: 'start' });
    dayIndexed.loadDayPage(day);
    // dayIndexed itself deliberately excluded - it's a fresh object every render (see
    // useDayIndexedPhotos.js's return statement), which would recreate this callback every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dayIndexed.loadDayPage]);

  const { saveScrollPosition } = useScrollRestoration(mainContentRef, isLightboxOpen, effectivePhotos);

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
  } = useAppNavigationAndSelection(effectivePhotos, currentDuplicateGroup, backgroundLocation, effectivePath, saveScrollPosition);

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

  // Header/Lightbox button visibility - pure client-side derivation now (was POST
  // /api/photos/selected). Depends on `spaces` for the current space's MAIN-or-not flag, so it
  // sits below useSpaceManagement.
  const photoSelection = useMenuSelection({
    pathname: effectivePath,
    currentSpaceId,
    currentAlbumId,
    isLightboxOpen,
    selectedAlbumIds,
    selectedSpaceIds,
    selectedIds,
    selectedPhoto,
    spaces,
  });


  /* Define the actual refreshAndMaintainScroll function using useCallback */
  const actualRefreshAndMaintainScroll = useCallback(async () => {
    const scrollPosition = mainContentRef.current?.scrollTop;

    if (effectivePath.startsWith('/duplicates')) {
      await fetchDuplicates(duplicatesPage);
      return;
    }

    // Re-fetch the day list/counts and refresh whatever's already loaded in place (see
    // useDayIndexedPhotos.refreshLoadedDays for the trade-off vs a full reset).
    await dayIndexed.refreshLoadedDays();

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
    // dayIndexed itself deliberately excluded - see seekToMemoryDay's own comment above.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [effectivePath, fetchAlbums, fetchSpaces, fetchGarbageSpaces, duplicatesPage, fetchDuplicates, dayIndexed.refreshLoadedDays, selectedMenu, mainContentRef]);

  /* Update the refreshAndMaintainScroll state once the actual function is defined */
  useEffect(() => {
    setRefreshAndMaintainScroll(() => actualRefreshAndMaintainScroll);
  }, [actualRefreshAndMaintainScroll]);

  // handleMoveToGarbage updates whatever's already loaded in place instead of a full refetch (see
  // usePhotoActions.js). See utils/photoListUpdates.js for the actual removal/duplicate-scrubbing
  // logic.
  const removePhotosLocally = useCallback((ids) => {
    dayIndexed.setPhotosByDay(prev => removeFromPhotosByDay(prev, ids));
    // dayIndexed itself deliberately excluded - see seekToMemoryDay's own comment above.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dayIndexed.setPhotosByDay]);

  const {
    handleMoveToGarbage,
    handleUnlinkDuplicate,
    handleMoveToMain,
    handleRestore,
    handlePermanentDelete,
    handleUpdateDate,
    handleEditPhoto,
    showEditDateModal,
    setShowEditDateModal,
    handleBulkUpdateDate,
    handleDownload,
    handleReorderPhotos,
  } = usePhotoActions(refreshAndMaintainScroll, setSelectedPhoto, handlePhotoClick, selectedIds, setSelectedIds, removePhotosLocally, fetchAlbums, fetchSpaces);

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

  const handleToggleDuplicationRegrouping = useCallback(async (enabled) => {
    if (duplicationRegrouping === enabled) return;
    try {
      const response = await fetch('/api/user/me/duplication-regrouping', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ duplicationRegrouping: enabled }),
      });
      if (response.ok) {
        const updated = await response.json();
        setDuplicationRegrouping(updated.duplicationRegrouping);
        await dayIndexed.refreshLoadedDays();
      } else {
        console.error('Failed to update duplication regrouping preference');
      }
    } catch (error) {
      console.error('Error updating duplication regrouping preference:', error);
    }
  }, [duplicationRegrouping, setDuplicationRegrouping, dayIndexed.refreshLoadedDays]);

  if (loadingAuth) {
    return <div>{messages["loadingAuthentication"]}</div>;
  }

  // Shared by "/" and "/asset/:photoId" (Library) AND "/garbage"'s embedded grid (Garbage) -
  // /asset/:photoId's effectivePath resolves to whatever backgroundLocation it was opened from
  // (see useRouteContext). MemoriesBar is Library-only (gated on selectedMenu, which already
  // correctly resolves to 'garbage' rather than 'library' for the Garbage instance of this).
  const libraryPhotoGrid = (
    <>
      {selectedMenu === 'library' && <PersonMergeSuggestionBanner />}
      {selectedMenu === 'library' && <MemoriesBar onSeekToDay={seekToMemoryDay} isLightboxOpen={isLightboxOpen} />}
      {selectedMenu === 'library' && (
        <RecentlyAddedSection key={duplicationRegrouping ? 'regroup' : 'plain'} baseUrl="/api/photos/recent" onPhotoClick={handlePhotoClick} isLightboxOpen={isLightboxOpen} />
      )}
      <PhotoGrid
          dayIndex={dayIndexed}
          type={selectedMenu}
          selectedIds={selectedIds}
          setSelectedIds={setSelectedIds}
          setSelectedPhoto={handlePhotoClick}
          isLightboxOpen={isLightboxOpen}
          handleMoveToGarbage={handleMoveToGarbage}
          handleUnlinkDuplicate={handleUnlinkDuplicate}
          handleReorderPhotos={handleReorderPhotos}
      />
    </>
  );

  const albumDetailElement = (
    <AlbumDetail
        albums={albums}
        dayIndex={dayIndexed}
        selectedIds={selectedIds}
        setSelectedIds={setSelectedIds}
        handlePhotoClick={handlePhotoClick}
        isLightboxOpen={isLightboxOpen}
        handleMoveToGarbage={handleMoveToGarbage}
        handleReorderPhotos={handleReorderPhotos}
    />
  );

  const personDetailElement = (
    <PersonDetail
        dayIndex={dayIndexed}
        selectedIds={selectedIds}
        setSelectedIds={setSelectedIds}
        handlePhotoClick={handlePhotoClick}
        isLightboxOpen={isLightboxOpen}
        handleMoveToGarbage={handleMoveToGarbage}
        refreshAndMaintainScroll={refreshAndMaintainScroll}
        handleReorderPhotos={handleReorderPhotos}
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
                      setShowEditDateModal={setShowEditDateModal}
                      handleDownload={handleDownload}
                      mediaTypeFilter={mediaTypeFilter}
                      setMediaTypeFilter={setMediaTypeFilter}
                      duplicationRegrouping={duplicationRegrouping}
                      onToggleDuplicationRegrouping={handleToggleDuplicationRegrouping}
                  />
              )}
            </UploadManager>
          )}

          <Routes location={backgroundLocation || location}>
            <Route path="/login" element={<Login checkAuthStatus={checkAuthStatus} />} />
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
                  <Route path="/persons/:personId" element={personDetailElement} />
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
                        dayIndex={dayIndexed}
                        selectedIds={selectedIds}
                        setSelectedIds={setSelectedIds}
                        handlePhotoClick={handlePhotoClick}
                        isLightboxOpen={isLightboxOpen}
                        handleMoveToGarbage={handleMoveToGarbage}
                        handleReorderPhotos={handleReorderPhotos}
                    />
                  } />
                  <Route path="/share-links" element={<ShareLinksManagement />} />
                  <Route path="/immich" element={<ImmichImportManagement />} />
                  {userRole === 'ADMIN' && (
                      <>
                        <Route path="/admin/users" element={<UserManagement />} />
                        <Route path="/admin/tasks" element={<TaskManagement />} />
                      </>
                  )}
                </>
            ) : (
                // Redirect all non-share, non-login paths to login if not authenticated
                <Route path="*" element={<Login checkAuthStatus={checkAuthStatus} />} />
            )}
            {/* Share routes are always accessible, and deliberately self-contained (see
                SharedAlbumView.jsx) — they never depend on isAuthenticated. */}
            <Route path="/share/:userMd5/:name" element={<SharedAlbumView />} />
            <Route path="/share/:userMd5/:name/p/:photoId" element={<SharedAlbumView />} />
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
            photos={effectivePhotos}
            currentDuplicateGroup={currentDuplicateGroup}
            onNavigate={handlePhotoClick}
            onUpdateDate={handleUpdateDate}
            onEditPhoto={handleEditPhoto}
            onShowAddToAlbumModal={handleShowAddToAlbumModalForLightbox}
            onShowRemoveFromAlbumModal={handleShowRemoveFromAlbumModalForLightbox}
            photoSelection={photoSelection}
            refreshAndMaintainScroll={refreshAndMaintainScroll}
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

        <EditDateModal
          show={showEditDateModal}
          onClose={() => setShowEditDateModal(false)}
          selectedIds={selectedIds}
          handleBulkUpdateDate={handleBulkUpdateDate}
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
          onClose={() => {
            setShowCreateShareLinkModal(false);
            setAlbumIdForShareLink(null);
            setShareLinkCreationError(null);
            setNewShareLinkName('');
            setNewShareLinkPassword('');
            setNewShareLinkExpiresInDays(7);
          }}
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