# IPHOTO Application Specification

This document outlines the functionality and behavior of the IPHOTO web application, a comprehensive photo management platform.

## 1. Core Concepts

*   **Photo:** The basic entity in the application. A photo belongs to one and only one **Space** at any given time. Photos have metadata, including date, location, and associated people.
*   **Space:** The primary container for photos. Each photo resides in a single space.
    *   **Main Space:** The default space. Photos in this space are displayed in the "Library" menu. Users can move photos from any other space into the Main space.
*   **Album:** A virtual collection or a "playlist" of photos. An album contains links (references) to photos that exist **in the Main Space only**. A single photo can be included in multiple albums without being moved or duplicated. The visibility of albums (i.e., which albums are displayed to the user) is managed by the backend.
*   **Library:** The view that displays all photos residing in the "Main" space.
    *   **Memories bar:** a horizontal strip above the day-sectioned grid showing up to
        `photosPerBlock` random Main Space photos (auto-cycled, see `components/MemoriesBar.jsx`)
        per configured "N months ago" offset — e.g. "1 month ago," "1 year ago," "5 years ago."
        Offsets come from `IPhotoProperties.Memories.offsets` (`iphoto.memories.offsets` in
        `application.yml`, default `[1, 6, 12, 36, 60, 120]`), not hardcoded — change the property
        to change which offsets show. Each entry is a plain integer (exact day, `today.minusMonths
        (N)`, matched against `groupedDay`) or that integer plus a day-tolerance suffix parsed by
        `MemoryOffset` — `"6-2d+3d"` (order-independent, so `"6+3d-2d"` is identical) searches every
        day from 2 days before to 3 days after the anchor day instead of requiring an exact hit.
        An offset with nothing in its window is simply omitted, not shown as an empty slot.
        Backend: `GET /api/photos/memories` (`PhotoService.getMemories`). Frontend:
        `hooks/useMemories.js` + `components/MemoriesBar.jsx`, rendered only for the Library view
        (not Garbage/Album/Space/Person), clicking a memory opens it in the Lightbox the same way
        any grid photo click does.
*   **Timeline scrubber:** every day-indexed view (Library/Album/Space/Garbage/Person, plus the
    share-guest flow) shows a right-edge vertical scrubber (`components/TimelineScrubber.jsx`) with
    one label per year. Click or drag-and-release jumps straight to that day's section — an
    instant, not animated, scroll, so nothing between the current position and the target is ever
    fetched (a `PhotoGrid.jsx` day section is rendered as a stub for every day up front regardless
    of load state, so this works for any day whether or not it's been loaded yet). Purely frontend;
    no new backend endpoint — reuses whichever `dayIndex.days`/`loadDayPage` the view already has.
*   **Garbage:** A temporary holding place for deleted photos and spaces before permanent deletion.
    It has two distinct sections, both shown on the `/garbage` view:
    *   **Deleted Spaces section:** Spaces that were deleted (soft-delete) from the `/spaces` menu.
        A deleted space behaves like a standard asset: it can be **restored** or **permanently
        deleted**. Permanently deleting a space cascades — all photos still inside it are
        permanently deleted too. The **Main space can never be deleted** (enforced by the
        backend; the frontend does not special-case "Main" itself, it simply respects the
        `canDeleteSpace` capability flag from `utils/selectionCapabilities.js`, which is `false`
        for Main).
    *   **Deleted Photos/Videos section:** Individual photos/videos that were deleted on their
        own (moved to garbage while their space was untouched).
    *   **Important distinction:** if a whole space is deleted, the photos that were inside it
        (and were not already individually deleted before the space deletion) do **not** show up
        a second time in the Deleted Photos section — they remain visible only by browsing into
        the deleted space itself (`/spaces/{id}`, reached from the garbage's Deleted Spaces
        section). A photo appears standalone in the Deleted Photos section only if it was deleted
        individually (its space was never deleted).
    *   All of this cascade/visibility logic is implemented **entirely in the backend**; the
        frontend just calls the delete/restore/permanent-delete endpoints for spaces and photos
        and renders whatever the backend returns — it does not compute cascade behavior itself.
    *   The Lightbox can be opened from a photo in any context, including from within a deleted
        space or the Deleted Photos section.
*   **Duplicate:** The application can identify and group photos that appear to be duplicates.

## 2. Authentication and User Roles

*   **Login/Logout:** The application is protected by a login system. Users must authenticate to access any feature besides shared links.
*   **Authentication State:** The application actively checks the user's authentication status and redirects to the login page if they are not authenticated.
*   **User Roles:** The system supports at least two user roles:
    *   `USER`: Standard user.
    *   `ADMIN`: Has access to additional features, such as the User Management panel.
*   **User Management (Admin):** An admin-only section (`/admin/users`) exists for managing user accounts.
*   **Task Management (Admin):** An admin-only section (`/admin/tasks`, `TaskManagement.jsx`) lists
    every `TaskEntity` row (paginated, filterable by status/type; each row also shows how long its
    last run took, `TaskEntity.durationMs`, set start-to-finish by `TaskWorkerManagerImpl`) and
    lets an admin manually restart one or many tasks — resetting them back to `PENDING` (clearing
    any error) regardless of their current status. Each row also has a **Park**/**Unpark** toggle:
    Park sets the task's status to `PARKED` (every other field is left untouched) so it's simply
    never selected by any `poll*PendingTasks` query — every one of them filters on `PENDING`, so
    `PARKED` needs no extra exclusion anywhere; Unpark sets it straight back to `PENDING`. It also
    has a **Reprocessing** panel to re-run a whole pipeline stage:
    *Restart face extraction* / *Restart duplicate detection* reset every `ISOLATE_FACES` /
    `CHECK_DUPLICATION` task to `PENDING`; *Restart person recognition* (confirmed first, it's
    destructive) deletes every `PersonEntity` (plus their embedding buckets and merge suggestions),
    detaches every face and marks it `NEW`, then kicks the perpetual `FACE_RECOGNITION` task to
    run now — the detected faces themselves (detection + embeddings) are kept, only the grouping is
    thrown away. See §5.7.

## 3. Main Application Layout

The application is a Single Page Application (SPA) with a consistent layout:

*   **Sidebar:** A persistent navigation sidebar on the left, providing access to the main sections of the application (Library, Albums, Spaces, Map, etc.) and the logout button. **Responsive:** on screens ≤768px it becomes a collapsible drawer (`components/Sidebar.jsx` owns its own open/closed state) with a fixed hamburger toggle button, closing automatically on navigation.
*   **Header:** A context-aware header that appears above the main content. It displays actions relevant to the current view and selection (e.g., "Delete", "Add to Album", "Upload"). **Button visibility is computed client-side** by `utils/selectionCapabilities.js` (a pure function; `hooks/useMenuSelection.js` maps the current route + selection onto its inputs). Given the current menu (`"library"`/`"album"`/`"albums"`/`"space"`/`"spaces"`/`"garbage"`, or `"lightbox"` whenever the Lightbox is open, overriding the underlying page's menu), which space/album the view is scoped to, and the selection, it returns capability flags (`canBeRestored`, `canBeDeleted`, `canBeMovedToMain`, `canBeMovedToAlbum`, `canBeRemovedFromAlbum`, `canBeSetAsCover`, `canBePermanentlyDeleted`, `canEditDate`, `canDownload`, `canBeModified`) plus a nested `menuSelection` object with menu-level flags (`canUpload`, `canCreateAlbum`, `canDeleteAlbum`, `canCreateSpace`, `canDeleteSpace`, `canRestoreSpace`, `canPermanentlyDeleteSpace`, `canAddAAShareLinkToAlbum`). `Header.jsx` renders buttons purely by reading these flags. These are cosmetic — every mutating endpoint enforces its own authorization server-side (there used to be a `POST /api/photos/selected` + `POST /api/menu/selected` round-trip for this; removed, since the flags never were a security boundary). The one server-provided input is `Photo.inMainSpace` (drives the Lightbox "Move to Main" button). The "delete/restore selected" menu flags (`canDeleteAlbum`/`canDeleteSpace`/`canRestoreSpace`/`canPermanentlyDeleteSpace`) are true exactly when the current list has ≥1 selected row the bulk action can act on — `canDeleteSpace` additionally excludes the MAIN space, which can never be removed (the old `POST /api/menu/selected` had these inverted, so the buttons never actually appeared for a real selection).
*   **Main Content Area:** The central part of the screen that displays the primary content, such as photo grids, album details, or maps.
*   **Lightbox:** A full-screen modal view for inspecting a single photo in detail. It provides navigation to the previous/next photo and access to actions for that specific photo. **Responsive:** the photo and the metadata sidebar sit side-by-side on desktop, but stack vertically on screens ≤768px (metadata panel becomes a scrollable panel below the photo, capped at 45vh).
*   **Responsive design:** the app targets full phone support (breakpoints at 768px and 480px),
    not just desktop. See `App.css`'s "Responsive" section at the bottom of the file, plus
    per-page adjustments in `components/Login.css` and `components/UserManagement.css`.

## 4. Feature Breakdown

This section describes the application's features. For the corresponding backend API calls, see Section 5.

### 4.1. Photo Management

*   **Photo Grid:** The primary interface for browsing photos in the Library, Albums, Spaces, and
    Garbage. **All of these list views must behave identically**: paginated fetching (`page`/`size`
    query params), with the next page loaded automatically as the user scrolls (via
    `IntersectionObserver` in `components/PhotoGrid.jsx` — shared by every list view, so this is
    automatically consistent across menus by construction). When the user opens a photo (entering
    the Lightbox) and then navigates back, the previous scroll position/visualization must be
    restored rather than resetting to the top. This is implemented via `hooks/useScrollRestoration.js`
    (`saveScrollPosition`, called from `handlePhotoClick` in `hooks/useAppNavigationAndSelection.js`
    right before navigating to the Lightbox) — both hooks are called once in `App.jsx` and shared
    across all routes since they key off the shared `mainContentRef`/`photos` state, not
    per-menu state, so a fix here applies everywhere automatically.
    *   **Day-index fetch is gated on auth confirmation:** `hooks/useDayIndexedPhotos.js` takes an
        `enabled` param (App.jsx passes `isAuthenticated`) so its days-list fetch waits for
        `useAuth.js`'s `/api/user/me` check to resolve before firing. Without this, the fetch used
        to run unconditionally on mount, racing the auth check — on a cold load, or right after
        logging in (the hook's reset effect keys on a `targetKey` string that doesn't change across
        the `/login` → `/` navigation, so it never re-ran to retry), the days request could hit the
        backend before the session was recognized, get a 401 with an empty body back, and fail
        `res.json()` with "Unexpected end of JSON input" — with nothing ever retrying it until a
        full page refresh reran everything from scratch against an already-valid session. Toggling
        `enabled` false→true re-runs the fetch once auth is confirmed.
    *   **Duplicates is an intentional exception:** `/duplicates` (`DuplicatePhotoGrid.jsx`) shows
        one duplicate *group* at a time for manual comparison/decision, with explicit
        Prev/Next-group buttons rather than infinite scroll — this is a deliberate different UX
        (decide-per-group), not an oversight, and is not expected to follow the same
        pagination/scroll-restoration contract as the other list views.
*   **Inline duplicate grouping:** each `Photo` returned by the photo-listing endpoints can carry a
    `duplicates: Photo[]` field (backend `Photo.java`/`PhotoMapper`, populated via
    `DuplicationService.getDuplicates(PhotoEntity)`) — the other members of its duplicate cluster, if
    any. **Backend status: only the Library/Space day-index endpoints (`getPhotosForDay`, which
    every day-index-browsing view routes through `buildDedupedPage`) populate this; Garbage's
    `getGarbagePhotosForDay`, Person's `getPersonPhotosForDay`, and Album's `getAlbumPhotosForDay`
    do not** — not fixed here since this was frontend-only scope; the frontend degrades gracefully
    (`p.duplicates || []`) wherever it's absent, it just won't show grouping there until the backend
    adds it.
    `components/PhotoGrid.jsx` clusters each photo with its `duplicates` into one display item
    (`consumedIds` prevents a duplicate from also rendering as its own separate top-level card) and
    renders it two ways:
    - **Collapsed (default):** one card for the "primary" photo (the first one encountered in the
      list) with a `.duplicate-badge` pill (count + `Layers` icon) overlaid bottom-left. Clicking the
      badge expands the group; clicking the card itself behaves like any normal photo card (select or
      open Lightbox) — only the primary is individually interactable while collapsed.
    - **Expanded:** a `.duplicate-cluster` wrapper (dashed outline) with a real, always-in-flow
      header row (`.duplicate-cluster-header` — count label + collapse button) above a row of full
      cards, one per member, each sized larger (`EXPANDED_CLUSTER_CARD_SIZE = 360`, vs. the normal
      grid's 220) so the photos are actually comparable at a glance. The header is deliberately laid
      out in normal flow rather than as an absolutely-positioned overlay on the card row — an earlier
      version used a negative-offset floating collapse button, which could end up visually clipped
      under the sticky day-group header depending on scroll position.
      - Each expanded member also gets its own `.duplicate-remove-btn` (trash icon, bottom-left),
        calling `handleMoveToGarbage([memberId])` directly — a one-click removal, **only rendered
        while the group is expanded** (per explicit user request; the initial version relied solely
        on the existing select-checkbox + Header bulk-action flow, superseded here). The checkbox
        overlay is still present too, so bulk multi-select still works alongside the per-card icon.
        `handleMoveToGarbage` is threaded down from `usePhotoActions.js` through `App.jsx` →
        `PhotoGrid`/`AlbumDetail`/`SpaceDetail` (same confirm-dialog/refresh behavior as the Header's
        bulk action, since it's the exact same function).
*   **Photo Selection:** Users can select one or more photos in the grid to perform bulk actions.
    Shift+click selects the contiguous range between the last-clicked photo and the one just
    clicked (standard range-select, like Finder/Gmail/Photos apps), in addition to the normal
    click-to-toggle behavior. Implemented once in `components/PhotoGrid.jsx`, so it applies
    uniformly to every menu that uses it (Library, Garbage, Album detail, Space detail). The
    Duplicates comparison view (`DuplicatePhotoGrid.jsx`) has its own separate, simpler selection
    UI and does not use this range-select behavior.
*   **Drag-to-reorder within a day:** hovering a photo card (`PhotoGrid.jsx`, everywhere it's used
    with a `handleReorderPhotos` prop — Library/Album detail/Space detail/Person detail; never on
    `SharedAlbumView.jsx`'s public grid) shows a translucent circular drag handle. Dropping it
    before/after another card **in the same day** re-stamps the moved photo's `dateTaken` so the
    grid's existing newest-first sort reflects the new position; dragging one of several
    currently-selected cards moves the whole selection together (any selected id from a *different*
    day is silently excluded — the same-day constraint applies per-item to a multi-drag too). The
    date math (`utils/photoReorder.js#computeReorderDates`) anchors off the neighbor that ends up
    displayed just *after* the moved item(s) (the older one) plus 1 second, cascading further
    seconds outward for a multi-item drag (falling back to a millisecond cascade in a tight gap) —
    reuses the existing `PATCH /api/photos/{id}/date` endpoint once per moved photo, no dedicated
    endpoint. Only primaries participate (a duplicate cluster's non-primary members aren't
    independently reorderable); desktop-only (no touch polyfill).
*   **Actions:** Every action below is gated by its own `photoSelection` capability flag from
    `utils/selectionCapabilities.js` (Section 3, Header) and, where noted, is available both as a
    Header bulk-action (multi-select) and from a single photo's own context (Lightbox).
    *   **Move to Garbage:** Soft-deletes selected photos (`canBeDeleted`).
    *   **Move to Main Space:** Moves selected photos from their current space to the default
        "Main" space (`canBeMovedToMain`).
    *   **Restore:** Restores photos from the Garbage back to their original space
        (`canBeRestored`).
    *   **Permanent Delete:** Permanently removes photos from the system — available for photos in
        the Garbage (`canBePermanentlyDeleted`).
    *   **Edit Date Taken:** Users can modify a photo's `dateTaken`, singly (Lightbox) or in bulk
        (`canEditDate`, `EditDateModal.jsx` + `usePhotoActions.js#handleBulkUpdateDate` — applies
        one exact timestamp to every selected photo, no relative offset). The drag-to-reorder
        feature above is a different way a photo's date can change, driven by position instead of
        a picked value.
    *   **Download:** A single selected photo downloads directly; multiple selected photos download
        together as one zip (`canDownload`, `usePhotoActions.js#handleDownload` — see Section 5.2
        for the single-vs-bulk endpoint split).
*   **Upload:** Users can upload new photos. A progress manager (`components/UploadManager.jsx`)
    shows the status of ongoing uploads, chunk-by-chunk; the completion response of
    `POST /api/upload/chunk` includes a `task` object (`{id, status, photoId}` — `unlinkedPhotoId` is
    generated up front at task creation, so it's valid immediately, not just once `COMPLETED`),
    which is then polled via `GET /api/tasks/{id}` until the backend processing task finishes.
    Once a queue item has a `unlinkedPhotoId` and its status is anything **other than `PENDING` or
    `FAILED`**, its file name becomes a clickable link (no separate "Show" button) that opens
    `components/UploadPreviewModal.jsx` — a small, standard-sized popup (not a full-screen
    takeover: transparent click-outside-to-close overlay, no dark backdrop, so the page behind
    stays fully visible) showing just the image/video, no metadata sidebar, no prev/next nav, no
    edit/delete actions — without navigating away from the current page. Note: the item's status
    can genuinely be `PROCESSING` (frontend-optimistic, set right when the last chunk lands)
    before later reverting to `PENDING` once polling reveals the backend worker hasn't actually
    picked up the task yet — during that `PROCESSING` window the link is clickable per spec, but
    the photo row may not exist yet server-side, so the popup can transiently show its loading
    state until `GET /api/photos/{id}` succeeds.
*   **Lightbox View:**
    *   Displays a high-resolution version of the photo.
    *   Shows extended metadata (camera, lens, etc.).
    *   Allows navigation between photos in the current collection (e.g., album, search results).
    *   Provides access to individual photo actions (delete, add to album, move to Main Space,
        etc.), each gated by its own `photoSelection` capability flag (e.g. "Move to Main Space"
        only shows when `canBeMovedToMain` is true — same client-side `selectionCapabilities.js`
        pattern as the Header, see Section 3).
    *   **Image editing (crop/rotate/zoom):** available when `photoSelection.canBeModified` is
        true. Uses `react-cropper` (`cropperjs`, `components/Lightbox.jsx`), not a full annotation
        suite — only crop, rotate (90° buttons, or drag Cropper.js's own rotate handle), and zoom
        (buttons, wheel, or pinch) are supported. The crop box is a real desktop-style
        draggable/resizable rectangle (Cropper.js's default `dragMode="crop"`), not a fixed
        zoom-and-pan window — starts covering the full image (`autoCropArea={1}`). "Save",
        "Save as New", and "Cancel" are all shown as soon as editing starts; there is no longer a
        "did anything change" gate (Cropper.js always has an active crop box, so that check from
        the previous `react-easy-crop`-based editor doesn't translate cleanly). The final image is
        produced client-side via `cropperRef.current.cropper.getCroppedCanvas(...)`, which renders
        at the crop box's actual full-resolution pixel size (not the on-screen preview size) and is
        then exported as a max-quality JPEG (`canvas.toBlob(..., 'image/jpeg', 1)`) and uploaded as
        a new file (`POST` through `onEditPhoto`).
    *   **Face-box overlay positioning gotcha:** the `<img>` inside `zoom-container` is centered via
        `object-fit: contain` + flex `alignItems`/`justifyContent: center`, so whenever the photo's
        aspect ratio doesn't exactly match the container's (nearly always), the rendered image sits
        inset from the container's edges by a letterbox gap on one axis. Any pixel math mapping
        `FaceEntity.x/y/w/h` (original-image pixel coordinates) onto screen coordinates — the
        `.face-box` overlays, and `clampPan`'s pan-clamping — MUST account for that gap (rendered
        size + centering offset), not just `naturalWidth/Height` vs. the container's/`<img>`'s raw
        `clientWidth/Height`. `.face-box` also needs its immediate parent (`zoom-container`) to
        carry `position: relative` itself — without it, `position: absolute` falls through to
        `.lightbox-content` (which has its own `padding: 40px`) as the positioning context, which
        was the other half of a real bug here (see `TODO.md`): boxes drawn at a position offset
        from the actual faces, differently per photo, even though backend detection/crops were
        correct throughout.

### 4.2. Organizational Features

#### Albums

*   **Concept:** An album is a flexible way to group photos for presentation or sharing. It contains references to photos from the **Main Space**, but does not "own" them.
*   **Create Album:** Users can create new albums with a name and description.
*   **Album Grid:** A dedicated view (`/albums`) displays all created albums.
*   **Album Detail:** Viewing an album shows a photo grid of its referenced photos.
*   **Add to Album:** Users can add selected photos (from the Main Space) to one or more existing albums. This creates a reference to the photo.
*   **Remove from Album:** Photos can be removed from an album's list of references without being deleted from their containing Space.
*   **Set Cover Photo:** A specific photo in an album can be designated as its cover image.
*   **Delete Album:** Albums can be deleted. This only deletes the collection of references, not the photos themselves.

#### Spaces

*   **Concept:** A space is the primary storage location for a set of photos. Moving a photo between spaces changes its fundamental location.
*   **Create Space:** Users can create new spaces to organize photos into distinct, separate collections (e.g., "Vacation 2023", "Work Project").
*   **Space Grid:** A dedicated view (`/spaces`) displays all created spaces.
*   **Space Detail:** Viewing a space shows a photo grid of the photos it contains.
*   **Delete Space:** Spaces (and the photos within them) can be moved to the Garbage. The Main
    space cannot be deleted. See Section 1 (Garbage) for how deleted spaces and their photos
    behave.
*   **Restore Space:** Spaces can be restored from the Garbage.
*   **Permanent Delete Space:** Spaces in the Garbage can be permanently deleted; this cascades to
    permanently delete all photos still inside the space.

### 4.3. Smart Features

*   **Duplicate Detection:**
    *   A dedicated view (`/duplicates`) allows users to review and manage photos that the system has identified as duplicates.
    *   Backend detection (`DuplicationService.java`) runs in two tiers per candidate pair, within the same Space:
        1.  **Global hash tier:** pHash and averageHash (OpenCV `img_hash`, 64-bit each) are compared via
            Hamming distance. A tight match (pHash ≤ 12 AND averageHash ≤ 10) confirms a duplicate
            directly — this is the cheap, fast path for near-exact re-uploads/recompressions.
        2.  **Feature-matching tier:** global hashes summarize the whole image as one signature, so they
            miss crops, rotations beyond EXIF correction, and localized edits. When hashes are merely
            "plausible" (pHash ≤ 24 OR averageHash ≤ 20) but not tight enough for tier 1, the service falls
            back to ORB keypoint matching + RANSAC homography (`confirmWithFeatureMatching` /
            `countHomographyInliers`) to confirm the two images share a consistent geometric structure,
            not just coincidentally-similar global hashes. This tier is only run on the "plausible" band,
            not on every pair, to keep cost bounded.
        *   **ORB descriptors are precomputed and persisted, not recomputed per comparison.**
            `PhotoEntity.orbDescriptors` (raw ORB descriptor matrix, row-major, 32 bytes/keypoint) and
            `PhotoEntity.orbKeypoints` (keypoint x/y as 4-byte floats, index-aligned with the descriptor
            rows) are computed once in `DuplicationService.preprocess`/`computeAndStoreOrbFeatures`, on the
            same rotated+resized grayscale image used for pHash/averageHash, and saved alongside them at
            upload/hash time. `confirmWithFeatureMatching` only reconstructs a `Mat`/point array from these
            stored bytes — it no longer re-reads either image from disk or re-runs ORB detection at
            comparison time. Bonus fix from this: the old per-comparison path loaded the image with
            `IMREAD_GRAYSCALE` directly and skipped `autoRotate`, so EXIF-rotated photos were being
            feature-matched unrotated; the persisted path is rotation-correct since it reuses `preprocess`'s
            pipeline. Photos hashed before this change have no stored descriptors — `confirmWithFeatureMatching`
            logs and returns `false` (not a crash) for those until they're re-hashed.
        *   Known limitation: candidates are still gated by the tier-1 hash band before feature matching
            runs, so a very heavy crop/zoom that pushes both hashes outside that band is still missed.
            Not fixed — would require running ORB on every pair in the space, which is a much bigger
            perf tradeoff than the current design.
        *   The homography-inlier check requires **both** an absolute count (`ORB_MIN_INLIERS`) **and**
            a minimum ratio of good matches that are inliers (`ORB_MIN_INLIER_RATIO`, currently 0.5).
            The ratio check exists because RANSAC finds the *largest* geometrically-consistent subset
            even among unrelated correspondences — on two unrelated photos that both contain repetitive
            texture (grass, wood grain, fabric folds), it's common to get 10+ "good" ORB matches that
            coincidentally agree on some transform, which cleared the count-only check and produced a
            confirmed false positive between two completely unrelated photos (a low double-digit inlier
            count out of a much larger good-match pool, e.g. 11/90). A genuine duplicate/crop/rotation
            has most of its good matches agree on one transform; a coincidental match on repeated
            texture has only a small fraction agree, hence the ratio floor.
        *   Diagnostic logging: `evaluatePair` logs hash distances and the tier-by-tier verdict for every
            compared pair; `confirmWithFeatureMatching` logs keypoint/good-match/inlier counts against
            their thresholds; `checkDuplication` logs the per-photo scan window and final duplicate set.
            Added to debug a false-positive report — check these logs first before assuming the fix
            requires tightening a threshold rather than a genuine grouping/logic bug.
    *   Grouping (`DuplicationUniquenessService.consolidateDuplicates`) treats duplicate clusters as
        connected components, not per-pair matches: when a newly-detected duplicate touches one or more
        existing `DuplicateEntity` groups (`findByPhotoDuplicatesPhotoIdIn`), all of them are merged into a single
        group (one kept id, `hash` recomputed from the full membership, the other touched groups
        deleted) rather than only ever extending the first candidate. A previously `CLOSED` group is
        reopened to `OPEN` when a new duplicate is folded into it, since it now has a new photo to
        review. This replaced an earlier version of the join condition that was self-contradictory
        (required the existing group to be both a subset of, and larger than, the newly-detected set)
        and was therefore unreachable — every detected duplicate used to spawn its own overlapping
        group instead of joining/merging with existing ones.
    *   **Async scheduling** (`TaskWorkerManager`): `CHECK_DUPLICATION` is polled on its own
        `pollDuplicationTask` scheduler tick (`fixedDelay=30_000` — every 30s), independent from
        `FACE_RECOGNITION`'s `pollFaceRecognitionTask` (`fixedDelay=300_000` — every 5 min). Both
        used to be bundled under one shared `pollHeavyTasks()` tick running every 5 minutes; split
        apart since duplicate detection is comparatively fast and something a user is actively
        waiting on right after an upload, unlike face-recognition's full re-cluster-everyone pass.
        Each still runs against its own dedicated, fixed-size `ThreadPoolTaskExecutor`
        (`duplicationThreadPoolExecutor`/`faceRecognitionThreadPoolExecutor`, sized from
        `iphoto.heavy-tasks.duplication-threads`/`face-recognition-threads` — both default 1, since
        each service processes a whole overlapping data set per run and isn't safe to run
        concurrently with itself), and both remain gated behind the same admin
        `AdminService#isHeavyTasksActivated`/`ACTIVATE_HEAVY_TASKS` on/off switch.
*   **Map View:**
    *   A world map (`/map`) displays photos based on their embedded geolocation data.
*   **Persons View:**
    *   A dedicated view (`/persons`) suggests functionality related to facial recognition and grouping photos by people.
    *   Backend pipeline (`FaceDetectionService` + `FaceAligner` + `FaceRecognitionService`): **SCRFD**
        (InsightFace's `buffalo_l` pack, `det_10g.onnx`, run via DJL's ONNX Runtime engine —
        `FaceDetectionConfiguration`) detects face boxes + 5-point landmarks on the **original,
        full-resolution** photo (not the resized-for-hashing copy `DuplicationService` uses — detection
        needs the extra pixels). Each detected box is padded 20% and, if it clears the size/confidence
        gates below, run through `FaceAligner.align` (rotates level using the eye-line landmarks, crops
        with its own 20% padding, resizes to 112×112 via bicubic interpolation) before going through the
        embedding model — **ArcFace R50** (`w600k_r50.onnx`, same `buffalo_l` pack, 512-dim embeddings).
        Both `.onnx` files are vendored under `src/main/resources/models/` (committed via git-lfs, not
        downloaded at build/runtime) so the app has no external model-hosting dependency. Embeddings + a
        JPEG thumbnail (`FaceDetectionServiceImpl.generateFaceThumbnail`, capped to
        `FACE_THUMBNAIL_MAX_SIZE=300px`, since it's only ever displayed at ~100px CSS size — Persons.jsx's
        `.person-item img`, Lightbox's "Faces Detected" strip) are saved per `FaceEntity`;
        `FaceRecognitionService.groupFaces` clusters them into `PersonEntity`s. Before this was fixed
        (2026-09-11), the thumbnail was saved uncropped-to-size as a lossless PNG at the crop's full
        original-photo resolution — a single face could be 1+MB, and the Persons page fetches one per
        person per page, which is what made `GET /api/faces/persons` balloon to ~70MB/7s to load; existing
        already-stored thumbnails from before the fix remain oversized until re-processed (no migration
        path yet — see `TODO.md`). (An earlier version of this pipeline used RetinaFace + PyTorch; replaced
        outright, no compatibility shim — old `FaceEntity`/`PersonEntity` rows are meaningless against
        the new embedding space and were truncated rather than migrated when this shipped.)
    *   **Small-face gating** (`FaceDetectionService`): faces under 60px (post-padding) are discarded
        outright unless they clear `MIN_FACE_SIZE=32` (hard floor — below this there's genuinely not
        enough resolution) **and** the detector's confidence is ≥`SMALL_FACE_MIN_CONFIDENCE=0.92`
        (stricter than the detector's own `confThresh=0.5`) — small crops carry more false-positive
        risk than large ones, so they need a higher bar to be kept at all. `FaceAligner`'s eye-line
        rotation must read both eye landmarks off the same iterator instance (an earlier bug reading
        both from a fresh iterator made the rotation a permanent no-op) — tilt correction matters more
        for small crops, where it eats a bigger fraction of the little available signal.
    *   **Age-invariant clustering** (`FaceRecognitionService`): a person's face embedding drifts
        meaningfully over years (e.g. age 5 vs. age 25), so a single flat cosine-similarity threshold
        between two far-apart-in-age photos of the same person will usually never clear the bar
        directly — and shouldn't be loosened to compensate, since that raises the false-merge risk for
        genuinely different people. Two separate paths handle this:
        - **Matching against an existing person** (`findBestMatchForFace`): compares a face against
          that person's own precomputed `PersonEmbeddingBucketEntity` rows — one running-average
          embedding per person per calendar year of their photos (bucketed by year, not one global
          average, specifically so a childhood era and an adult era don't dilute each other's
          signature), weighted toward the 2 nearest-by-year buckets. Buckets are maintained
          incrementally (`PersonRecognitionService#addFaceToBucket`/`removeFaceFromBucket`) every time
          a face is attached to, detached from, or merged into a person, rather than recomputed from
          scratch per match.
        - **Clustering leftover ungrouped faces into brand-new people** (`CandidateClusterer`, run
          from `FaceRecognitionService#groupFacesForUser`): faces are fetched oldest-`dateTaken`-first
          in bounded slices (`CLUSTERING_SLICE_SIZE=200` at a time, capped overall at
          `MAX_UNGROUPED_FACES_PER_RUN`) rather than all at once, to bound memory — a single prolific
          real person's faces can no longer pin an unbounded, ever-growing in-memory cluster the way an
          earlier full union-find implementation could. Each open candidate cluster holds at most 3
          representative faces; an incoming face joins the first cluster whose representative(s) it
          matches (cosine similarity ≥ `THRESHOLD=0.60` **and** within `MAX_NEW_PERSON_CLUSTER_GAP`
          (90 days) of at least one of them), or seeds a new one-member cluster otherwise. A cluster
          promotes to a real `PersonEntity` the instant it reaches `MIN_NEW_PERSON_CLUSTER_SIZE=3`
          members, using exactly those 3 faces as both representatives and the promotion payload —
          faces 4+ for that same person then attach via the normal existing-person bucket-matching path
          above, including newly-promoted people from earlier in the same run (so a later slice can
          match a person promoted from an earlier slice instead of risking a duplicate). Chronological
          processing order is what lets transitive chaining still bridge large age gaps despite the
          per-cluster representative cap — a childhood, then teenage, then adult photo of the same
          person are picked up as the timeline progresses rather than compared all-at-once. A person
          with only two photos taken decades apart, with nothing in between, still won't cluster
          together — an inherent limitation of any temporally-windowed approach, not a bug.
    *   **`FaceEntity.status` (`NEW`/`OLD`)**: every face starts `NEW` (`FaceDetectionServiceImpl`).
        `groupFacesForUser` only runs the expensive existing-person analysis (`findBestMatchForFace`,
        checked against every person's buckets) for `NEW` faces - an `OLD` one already went through
        that check in a past run and found nothing, and skipping it is what stops a large,
        permanently-unmatched backlog (faces that will never cluster into anyone) from being
        re-analysed against every person on every ~30s run forever. A face flips to `OLD` once
        analysed this way, regardless of outcome (matched, promoted into a brand-new person via
        `CandidateClusterer`, or still an unclustered singleton) - see `PersonRecognitionService
        .updatePerson`/`addPerson` (matched/promoted cases) and `groupFacesForUser`'s own explicit
        flip (unresolved case). `OLD` faces are still fed into `CandidateClusterer` every run exactly
        like `NEW` ones, though - clustering itself is unaffected by status, since a brand-new person
        can only be discovered once enough of their faces exist, old and new alike, and a face that
        completes a cluster stays valid evidence regardless of when it was first seen.
    *   **Space scoping during face grouping**: `MainSpaceAspectConfiguration.enableSpaceFilter`
        skips enabling the per-user `spaceFilter` Hibernate filter for any thread named
        `backend-task*`/`heavy-task*`, and `FACE_RECOGNITION` (like all `TaskWorkerManager`
        background tasks) always runs on such a thread — so a plain `@MainSpaceLimited` query would
        be silently unfiltered there (this was a real bug for a while: `groupFacesForUser` used to
        call `@MainSpaceLimited` `personRepository.findAll()`/`faceRepository.
        findByPersonIsNullFetchPhoto`, which pooled every user's faces/persons together on a
        production run, letting a cluster mix faces from different accounts into one `PersonEntity`).
        Fixed by resolving the target user's space explicitly once (`SpaceService
        .getDefaultSpaceForUser`) and querying with it directly — `PersonRepository.findAllBySpaceId`/
        `FaceRepository.findByPersonIsNullAndSpaceIdFetchPhoto` — instead of relying on
        `@MainSpaceLimited`/`SecurityContextHolder` at all, which is correct regardless of which
        thread the query runs on.
    *   **Cover photo (`Persons.jsx`)**: each person's icon on `/persons` is their `coverFace` —
        `PersonRecognitionService.recalculateCoverFace` picks the face whose photo has the most
        recent valid `dateTaken` (i.e. "latest known photo of them"), recalculated whenever a face
        is added to the person (`updatePerson`/`addPerson`) or when their previous cover face's
        photo is deleted (`FaceDetectionService.deleteFaces`) — never overridden automatically once
        a user manually sets one (`CoverFaceOrigin.MANUAL` guard). `coverFace` can still legitimately
        be `null` (a person with zero remaining faces, or before any face-add/delete event has ever
        triggered a recalculation for them) — `FaceService`/`Persons.jsx` both handle this as a
        normal "no photo yet" case (`coverPhoto: null` in `PersonDTO`, a placeholder icon on the
        frontend), not an error state.
    *   **Person detail page** (`/persons/:personId`, `PersonDetail.jsx`): clicking a person's
        photo/icon on `/persons` navigates here. Deliberately mirrors `AlbumDetail.jsx`/
        `SpaceDetail.jsx` — same `PhotoGrid` (selection, duplicate grouping, garbage icon, etc.),
        same header shape (name + `photosCount`), same `useEntityById` pattern for fetching the
        entity. Backend: `GET /api/faces/person/{id}` (new — previously `FaceController` only
        exposed list/update/faces-for-person/photos-for-person, no single-person GET, so
        `useEntityById` had nothing to call); `PersonDTO.photoCount` is now a real count
        (`photoService.findByPerson(person, Pageable.ofSize(1)).getTotalElements()`) instead of
        always `null`. Photos come from the day-index endpoints `GET /api/faces/person/{id}/days` +
        `/photos/day/{day}` — every day that has one of this person's photos, fetched up front,
        each day's actual photos lazily fetched (paginated, 100/page) as its section scrolls into
        view, same pattern Library/Album/Space/Garbage use (see `hooks/useDayIndexedPhotos.js`) —
        wired into that hook's path-based routing (`/persons/:id` → `type: 'person'`) the same way
        `/albums/:id` and `/spaces/:id` already were. Opening a photo from here uses the plain
        `/asset/:photoId` URL (like Space, not Album's `/albums/:id/asset/:photoId`) — no
        per-person asset sub-route was added, since nothing needs a person-scoped deep link today.
        Also fixed in passing: `FaceService.getPersonPhotos` threw a plain `RuntimeException` for a
        missing person, which `FaceController`'s `catch (NoSuchElementException e)` could never
        actually catch (500 instead of 404) — changed to `NoSuchElementException` to match every
        other "get by id" endpoint's convention.
        **Person strip** (`PersonStrip.jsx`, shared `hooks/usePersons.js`): rendered above the
        header/grid on `PersonDetail.jsx` (including its loading/error/not-found states) — a
        horizontal, arrow-scrollable row of every person, the current one highlighted, so switching
        between people doesn't require returning to the `/persons` grid. `/persons` itself is
        unchanged — still the grid view, now just sourcing its data from the same shared
        `usePersons()` hook instead of its own local fetch.
        **Unlinking a person from one photo**: every card on a person's page has a top-right
        `Unlink2` icon (`.unlink-person-btn`) — `DELETE /api/faces/person/{personId}/photo/
        {photoId}` sets `person = null` on the face(s) linking that person to that specific photo
        (recalculating their `coverFace` first if affected), then the grid refreshes and the photo
        drops out of this person's list. This is deliberately different from the Lightbox's
        "remove face" action: unlinking only breaks the person↔photo match (e.g. correcting a
        wrong recognition match) — the `FaceEntity` (bounding box, embedding, thumbnail) and the
        photo itself are untouched. `PhotoGrid.jsx`'s `handleUnlinkPerson` prop is optional and
        only ever passed from `PersonDetail.jsx` — Library/Album/Space grids don't get this icon.
    *   **Manually removing a face** (Lightbox "Faces Detected" section): each face thumbnail has a
        small trash-icon button (`.face-remove-btn`) that deletes just that one `FaceEntity` via
        `DELETE /api/faces/{id}` — the photo itself is untouched, this only removes that one
        detection (e.g. a false positive). If the removed face was a person's `coverFace`,
        `PersonRecognitionService.recalculateCoverFace` picks a replacement immediately (same
        pattern as the whole-photo-deletion path in `FaceDetectionService.deleteFaces`). `FaceDTO`
        carries an `id` field for this to be possible at all — it previously had none.
    *   **Named face labels** (Lightbox "Faces Detected" section): a face thumbnail shows the
        linked person's name (`FaceDTO.personName`) instead of the generic "Face 1"/"Face 2"/...
        label whenever that face is attached to a named `PersonEntity`; falls back to the generic
        label otherwise (no person linked yet, or linked but unnamed).
    *   **Renaming/mapping a face's person from the Lightbox** (`Lightbox.jsx`, face label is now
        clickable/editable; `FaceService.setFacePerson`, `PUT /api/faces/{id}/person`): typing a
        name for a face resolves one of three ways, depending on whether that name already belongs
        to a *different* person than the face's current one:
        1.  **No collision, face already had a person** — plain rename: that `PersonEntity`'s name
            changes, so every one of their faces relabels (it's the same identity; a typo fix).
        2.  **No collision, face had no person yet** — creates a brand-new `PersonEntity` with that
            name for just this one face.
        3.  **Collision** (a different person already has that name) — this is not a rename; the
            backend returns `409` with that person's `PersonDTO` (cover face included) instead of
            applying anything, and `Lightbox.jsx` shows a confirmation popup (`mergePrompt` state)
            with the existing person's cover face next to this face's own thumbnail, asking to
            merge. Confirming resubmits with `confirmMerge: true`, which moves **only this one
            face** to the existing person — every other face the original person had is untouched.
            (A full person-to-person merge, moving *all* of a person's faces, is the separate
            `/persons/merge` feature below — deliberately not the same action.)
    *   **Unlinking a single face from its person, keeping the face** (Lightbox `Unlink2` button
        next to a named face, shown only when the face has a person; `FaceService.
        unlinkFaceFromPerson`, `DELETE /api/faces/{id}/person`): clears just that one face's
        `person` (recalculating the old person's `coverFace` if it was affected) without deleting
        the `FaceEntity` — narrower than `unlinkPersonFromPhoto` above (which unlinks *every* face
        tying one person to one photo; this unlinks one specific face regardless of how many other
        faces on the same photo share that person).
    *   **Merging two people** (`/persons` grid — a `.check-circle` selection overlay per person,
        shown on hover or once 2 are selected, plus a merge action bar; `PersonRecognitionService.
        mergePersons`, `POST /api/faces/persons/merge` with `{ personIds: [id1, id2] }`): for when
        clustering split one real person into two `PersonEntity` rows. Direction isn't
        caller-chosen — the person with **fewer** faces always merges into the one with **more**
        (ties broken arbitrarily), keeping the target's name and cover face unconditionally; the
        now-empty source person is deleted. Distinct from the single-face reassignment above: this
        moves *every* face the source person had.
    *   **Merge suggestions** (Library page): an extra step, run alongside face recognition
        (`PersonMergeSuggestionService`, invoked from `FaceRecognitionService.groupFacesForUser`
        right after that user's own face-to-person matching/clustering finishes), compares every
        existing person's *overall* embedding (a weighted average across every one of their
        `PersonEmbeddingBucketEntity` rows, not just the nearest-year ones ordinary face matching
        uses) directly against every other person's — surfacing a pair for a human to review
        whenever two people look similar enough to plausibly be the same real person, using a
        stricter threshold than the one used to attach a single face to a person (so this doesn't
        fire nearly as often as ordinary matching does). This never merges anything automatically —
        it only ever writes a `PersonMergeSuggestionEntity` row for the Library page to surface.
        - `components/PersonMergeSuggestionBanner.jsx` (Library only, rendered alongside
          `MemoriesBar.jsx` right below the header): shown whenever
          `GET /api/faces/persons/merge-suggestions/count` reports at least one live suggestion for
          the current user's space — a cheap, count-only read (no face thumbnails at all) that's
          all the banner text needs before the popup is even opened. Clicking "Review" opens
          `PersonMergeSuggestionModal.jsx`, fetching and showing one suggested pair at a time (cover
          face, name, photo count, similarity percentage for each side) via
          `GET /api/faces/persons/merge-suggestions?page=N&size=1` with three actions — **Merge**
          (calls the exact same `POST /api/faces/persons/merge` two-person flow described above —
          there is only ever one merge mechanism in the app, this doesn't add a second one), **Next**
          (fetches the next page/suggestion without acting on this one), and **Close** (dismisses
          the popup without merging or otherwise recording anything — the same suggestion is shown
          again next time the banner is opened). Deliberately paginated at `size=1` rather than one
          flat unpaginated list of every suggestion (the original design) — each entry embeds BOTH
          persons' full base64 cover-face thumbnails, and since the popup only ever displays one
          pair on screen at a time, fetching every other pair's face images too turned a real
          library's response into several megabytes and multiple seconds just to render a banner
          that, until opened, only needs a number.
        - A suggestion is upserted (not just inserted) on every run: if a previously-flagged pair's
          similarity has since dropped below the threshold (e.g. a face was unlinked or moved to a
          different person), the row is deleted instead of left stale. When two people are merged
          (through this flow or the plain `/persons/merge` action), any suggestion naming the
          now-deleted source is cascade-deleted at the DB level, and any suggestion naming the
          surviving target is explicitly discarded too (its embedding just changed), so the next
          scheduled run recomputes it fresh rather than leaving a stale suggestion in place.
    *   **Renaming a whole person** (`Persons.jsx`'s inline name edit; `PersonDetail.jsx`'s page
        title, click-to-edit like Lightbox's metadata fields; `FaceService.renamePerson`, `PUT
        /api/faces/person/{id}` with `{name, confirmed, mergeWithPersonIds}`) — a third, distinct
        rename/merge mechanism alongside the two above, for renaming the *entity itself* rather than
        one face's link to it:
        1.  **No collision** — plain rename, nothing else happens.
        2.  **Collision** (one or more *other* people already have that name — this app doesn't
            enforce name uniqueness at the DB level, so more than one can) — `409` with all of
            their `PersonDTO`s (cover face + photo count each); the caller (both `Persons.jsx` and
            `PersonDetail.jsx` share `hooks/usePersonRename.js` + `PersonMergePrompt.jsx` for this)
            shows a picker, one card per candidate — a name label wouldn't help tell them apart
            since they all share the exact name that collided, so each card shows its own face
            instead. The user selects zero or more to fold in; resubmitting with `confirmed: true`
            renames AND merges every selected id into the renamed person via
            `PersonRecognitionService.mergeInto` — a *fixed-direction* sibling of `mergePersons`
            above: the renamed person is always the keeper here, never chosen by face count, since
            the user explicitly picked it by renaming it. Selecting none is a valid, deliberate
            "rename anyway, don't merge with any of these" choice, not blocked.
    *   **`FaceLinkExclusionEntity`** (`face_link_exclusions` table) — the piece that makes manual
        unlinking actually stick. Every path that detaches a face from a person (the two unlink
        actions above, and the "move only this face" branch of a name-collision merge) records a
        `(face, excludedPerson)` row via `FaceService`'s shared `detachFaceFromPerson`/
        `attachFaceToPerson` helpers. `FaceRecognitionService.groupFacesForUser` loads every
        exclusion for the current batch of ungrouped faces once per run and filters that face's
        candidate person list before matching — so the ~30s automatic re-grouping pass can never
        silently reattach a face to a person the user explicitly removed it from, while the face
        remains completely free to match a *different* existing person, or seed a brand-new one,
        going forward (exactly "regroup it under another person"). Explicitly *not* recorded for
        `PersonRecognitionService.mergePersons`: that's a deliberate "these are the same person"
        declaration, not a correction, so future auto-matching against the merge target should
        freely include the moved faces as if they'd always belonged to it.
    *   **Bug fix, `PersonEntity`**: extends `BaseEntity`, whose `Persistable<UUID>.isNew()` reads
        a transient flag that every *other* `BaseEntity` subclass (`ShareEntity`, `AlbumEntity`,
        `SpaceEntity`) flips to `false` via `@PrePersist`/`@PostLoad` — `PersonEntity` never did,
        so `isNew()` returned `true` forever, even for a person freshly loaded from the DB. That
        made `personRepository.save()` always call `persist()` instead of `merge()`: harmless for
        a genuinely new person, but `InvalidDataAccessApiUsageException: detached entity passed to
        persist` the moment an *existing* person got saved from a different transaction than the
        one that loaded it (e.g. `PersonRecognitionService.updatePerson`, `@Transactional
        (REQUIRES_NEW)`). Fixed by adding the same two callbacks every sibling entity already has.

### 4.4. Sharing

A share link is owned by a user and points at exactly one album (`ShareEntity.album`). Anyone
with the link can view that album's photos read-only, with or without a password; the owner can
delete the link or remove its password at any time.

*   **Address scheme:** `/share/{userMd5}/{name}`, e.g. `/share/3a1f.../vacation-2024`. `userMd5`
    is `md5(user.id)` (`ShareService.computeUserMd5`, `org.springframework.util.DigestUtils`),
    computed once at share-creation time and stored on `ShareEntity.userMd5` — it identifies the
    owning user in the URL without exposing their real UUID. `name` only needs to be unique
    *within* one user's shares (enforced both by the `name`+`space_id` DB unique constraint and by
    `ShareRepository.findByName` being `@MainSpaceLimited`), so two different users can each have
    a share named `vacation-2024`; `userMd5` is what disambiguates them. Opening a specific photo
    is `/share/{userMd5}/{name}/p/{photoId}`.
*   **Creating a link:** `CreateShareLinkModal.jsx` / `useShareLinkManagement.js`, `POST
    /api/share` with `{ name, password, daysAvailable, albumId }`. The response (`ShareResponse`)
    now includes `userMd5`, so the success alert shows the actual shareable URL, not just the
    link name.
*   **Managing links:** `/share-links` (`ShareLinksManagement.jsx` + `hooks/useMyShares.js`) lists
    every share the current user owns (`GET /api/share/my-shares`) with its full URL, whether it's
    password-protected or expired, a "Remove Password" action (`PUT /api/share/{id}` with `{
    "password": null }` — clears the password without deleting the link) and a delete action
    (`DELETE /api/share/{id}`). Both are ownership-checked by `ShareRepository.findById` being
    `@MainSpaceLimited` — a user can only manage shares in their own space.
*   **Guest access flow — deliberately self-contained** (`SharedAlbumView.jsx`): the `/share/**`
    routes never depend on `isAuthenticated`/`useAuth.js` (that hook is for real accounts) and
    never touch `/albums/:albumId` (which only renders inside the authenticated app shell). A
    guest is instead authenticated into a backend-managed **`ROLE_SHARE`** session scoped to
    exactly one share/album (see below), and talks exclusively to endpoints built for that guest
    session:
    1.  `GET /api/share/{userMd5}/{name}` — returns 404 (not found), 410 (expired), or a status
        map. If **not** password-protected, this call *itself* establishes the guest's `ROLE_SHARE`
        session (there's no separate login step to skip) and returns `{ protected: false, albumId
        }`. If password-protected and not yet unlocked in this browser session, returns `{
        protected: true, shareId }` and `SharedAlbumView.jsx` renders an inline password form
        (no separate route/page for this — folded into the same component for simplicity).
    2.  `POST /api/share/{shareId}/login` with `{ password }` — verifies the (BCrypt-hashed)
        password, then establishes the same `ROLE_SHARE` session and returns the album id.
    3.  Once "ready", `SharedAlbumView.jsx` drives the **same day-indexed timeline** as every
        authenticated photo-listing view — it calls `hooks/useDayIndexedPhotos.js` with an
        explicit `{ type: 'shareAlbum', albumId }` target (the album id isn't in the route path,
        so it can't be path-derived), which points the hook at `GET
        /api/albums/share/{albumId}/days` (day counts up front) + `GET
        /api/albums/share/{albumId}/photos/day/{day}?page={n}` (each day's photos, lazily as its
        section scrolls in). Rendered via the same `PhotoGrid.jsx` used everywhere else (read-only
        here: no selection actions are wired up, just browsing/opening a photo). The `App.jsx`-level
        `useDayIndexedPhotos(effectivePath)` call still runs on `/share/**` routes but resolves to
        a no-fetch `share` target (its output is unused there — `SharedAlbumView` owns its own
        instance). Clicking a photo navigates to `/share/{userMd5}/{name}/p/{photoId}`, which additionally
        renders `SharedLightbox.jsx` — a deliberately minimal full-screen viewer (prev/next/close
        only; none of the main `Lightbox.jsx`'s zoom/pan/date-editing/face-detection/delete
        controls make sense for a guest with no account). It fetches the single photo via `GET
        /api/albums/share/{albumId}/photos/{photoId}`.
    *   **Guarding against clobbering a real login:** if the browser already has a normal
        authenticated session (e.g. the owner previewing their own link in the same tab),
        `ShareController` skips establishing a `ROLE_SHARE` session rather than silently replacing
        the real login — see `isGuestOrAnonymous` in `ShareController.java`.
*   **Authorization boundary for guests — `ShareAccessFilter`** (`configuration/`, replaces the
    older, broken `ShareAlbumFilter`): a `ROLE_SHARE` authentication satisfies Spring Security's
    blanket `anyRequest().authenticated()`, same as any real user — so without an extra check, a
    guest could reach *any* album/photo by UUID, not just the one their link grants (the photo/
    album repositories used by the guest flow are intentionally **not** `@MainSpaceLimited`, since
    a guest session has no matching `UserEntity` for that aspect to resolve — see below). This
    filter is the actual authorization boundary: for any `/api/albums/share/**` or
    `/api/photos/{id}/**` request made under a `ROLE_SHARE` session, it loads the `ShareEntity`
    the guest authenticated against (via the session's `SHARE_ID` attribute) and confirms the
    requested album — or the album the requested photo belongs to — matches. Anything else gets a
    403. Non-guest requests pass through unchanged.
*   **Why guest lookups bypass the space filter:** `MainSpaceAspectConfiguration`'s aspect (see
    §4.3/known limitation notes elsewhere in this doc) resolves the *current user* from
    `Authentication.getName()` to enable Hibernate's `spaceFilter`. A guest's synthetic principal
    (`"share-" + shareId`) isn't a real `UserEntity`, so any `@MainSpaceLimited` repository method
    called during a guest request throws `NoSuchElementException` (user not found) — this is
    exactly what made the password-protected share flow 500 before this pass. The fix was **not**
    to touch `MainSpaceAspectConfiguration` (left alone deliberately, as elsewhere in this
    codebase), but to add unfiltered, guest-safe lookups used only by the share flow:
    `ShareRepository.findByIdIgnoringSpace`/`findByUserMd5AndName`,
    `AlbumRepository.findByIdIgnoringSpace`, and `AlbumService.getAlbumPhotosForShare`/
    `getAlbumPhotoForShare`/`getAlbumDailyPhotoCountsForShare`/`getAlbumPhotosForDayForShare` (kept
    separate from the existing, still-filtered
    `getAlbumPhotos`/`getAlbumDailyPhotoCounts`/`getAlbumPhotosForDay`/`getAlbum`, which the
    *authenticated* album-detail view still uses — reusing
    the filtered version for guests would 500 them; reusing the unfiltered version for real users
    would remove their space isolation entirely).

## 5. Backend API Endpoints

This section lists the primary API endpoints the frontend application calls to interact with the backend.

### 5.1. Authentication
*   `GET /api/user/me`: Checks if the current user is authenticated and returns their role.
*   `POST /api/logout`: Ends the current user's session.

### 5.2. Photo & Asset Management
*   `GET /api/photos?page={n}&selection={menu}&albumId={id}&spaceId={id}`: Fetches a paginated list of photos for the specified context (library, album, space, garbage).
*   `POST /api/photos/upload?path={effectivePath}`: Uploads one or more photo files.
*   `POST /api/photos/garbage`: Moves a list of photos to the garbage.
*   `POST /api/photos/garbage/restore`: Restores a list of photos from the garbage.
*   `DELETE /api/photos/garbage/permanent`: Permanently deletes a list of photos.
*   `POST /api/photos/main`: Moves a list of selected photos to the Main Space.
*   `PATCH /api/photos/{id}/date`: Updates the date for a single photo. Bulk "Edit Date Taken"
    (`canEditDate`) has no dedicated bulk endpoint — the frontend calls this once per selected
    photo via `Promise.all` instead (same pattern the bulk add-to-album action uses). Drag-to-
    reorder-within-a-day does the same, once per moved photo. Parses `newDate` via
    `DateTimeFormatter.ISO_LOCAL_DATE_TIME` (not a fixed minute-only pattern) specifically so it can
    carry the second/millisecond precision drag-to-reorder needs, while staying backward compatible
    with the `datetime-local` picker's minute-only strings (seconds/nanos are optional in that
    formatter).
*   `POST /api/photos/{id}/edit`: Edits a photo.
*   `GET /api/photos/{id}/metadata`: Fetches extended metadata for a single photo.
*   `GET /api/photos/{id}/download`: Downloads a single photo as an attachment
    (`Content-Disposition: attachment`). Deliberately a separate route from
    `GET /api/photos/{id}/original` — that endpoint is used as an `<img src="...">` for in-page
    rendering all over the frontend (Lightbox full-res view, upload preview, duplicate-cluster
    modal, shared lightbox), and a browser won't render an image inline from a response marked as
    an attachment.
*   `POST /api/photos/download`: Bulk "Download" (`canDownload`) for multiple selected photos —
    body is a JSON list of photo IDs (POST, not GET+query-string, since a large selection could
    exceed a comfortable URL length). Streams back a zip (`StreamingResponseBody` wrapping a plain
    `ZipOutputStream`, never buffering the whole zip in memory) containing only photos the caller
    actually owns (`PhotoService.findOwnedByIds` silently drops any other id — the authorization
    boundary here, mirroring the ownership check bulk-delete already does), with filename
    collisions within one zip de-duplicated by appending a suffix from the photo's own id.
*   `GET /api/map-assets?path={effectivePath}`: Fetches assets to be displayed on the map view.
*   *(removed)* `POST /api/photos/selected` and `POST /api/menu/selected` — the toolbar/Lightbox
    button-visibility flags they returned are now computed client-side in
    `utils/selectionCapabilities.js` (see Section 3, Header). They were never an authorization
    boundary; the mutating endpoints each enforce ownership themselves.

### 5.3. Album Management
*   `GET /api/albums`: Fetches the list of all albums visible to the user.
*   `POST /api/albums`: Creates a new album.
*   `POST /api/albums/add-photos`: Adds a list of photos to a list of target albums.
*   `POST /api/albums/remove-photos`: Removes a photo from a specific album.
*   `DELETE /api/albums`: Deletes one or more albums.
*   `POST /api/albums/set-cover-photo`: Sets a specific photo as the cover for an album.

### 5.4. Space Management
*   `GET /api/spaces`: Fetches the list of all non-garbage spaces.
*   `GET /api/spaces/garbage`: Fetches the list of spaces currently in the garbage.
*   `POST /api/spaces`: Creates a new space.
*   `DELETE /api/spaces/{id}`: Moves a single space to the garbage.
*   `POST /api/spaces/restore`: Restores a list of spaces from the garbage.
*   `POST /api/spaces/permanent-delete`: Permanently deletes a list of spaces from the garbage.

### 5.5. Duplicate Management
*   `GET /api/duplicates?page={n}`: Fetches a paginated list of duplicate photo groups.

### 5.6. Sharing
Owner-facing (real authenticated user, ownership enforced via `@MainSpaceLimited`):
*   `POST /api/share`: Creates a new share link for an album (`{ name, password, daysAvailable, albumId }`); response includes `userMd5` for building the full URL.
*   `GET /api/share/my-shares`: Lists every share link the current user owns.
*   `GET /api/share/{id}`: Fetches one owned share by its id.
*   `PUT /api/share/{id}`: Updates a share's password (`{ "password": null|"..." }` — null/blank removes password protection).
*   `DELETE /api/share/{id}`: Deletes a share link.

Guest-facing (public, establishes/uses a `ROLE_SHARE` session — see §4.4):
*   `GET /api/share/{userMd5}/{name}`: Resolves a share link; 404/410/`{protected, albumId|shareId}`.
*   `POST /api/share/{shareId}/login`: Verifies a share's password and establishes the guest session.
*   `GET /api/albums/share/{albumId}/photos?page={n}`: Paginated photos for the album a guest's share grants access to (`ShareAccessFilter`-gated).
*   `GET /api/albums/share/{albumId}/days`: Day-index counts for the shared album — guest-scoped mirror of `GET /api/albums/{id}/days` (`ShareAccessFilter`-gated).
*   `GET /api/albums/share/{albumId}/photos/day/{day}?page={n}`: One day's photos for the shared album (`day` = ISO `yyyy-MM-dd`); guest-scoped mirror of `GET /api/albums/{id}/photos/day/{day}`.
*   `GET /api/albums/share/{albumId}/photos/{photoId}`: A single photo, only if it belongs to that album (backs the shared Lightbox).
*   `GET /api/photos/{id}/thumbnail` / `/original` / `/stream`: Same asset endpoints real users use — guests can only reach the photo their `ShareAccessFilter`-checked album contains.

### 5.7. Admin
*   `POST /api/admin/storage/migrate-flat-files`: One-time, idempotent backfill moving any
    pre-existing flat-stored photo/thumbnail into the `{year}/{month}` storage layout (see §6).
    Not run automatically — an admin triggers it explicitly.
*   `GET /api/admin/tasks?status=&taskType=&page=&size=`: Paginated `TaskAdminDTO` list backing
    the Admin > Tasks page — `status`/`taskType` are both optional, independent filters (either or
    both may be omitted); sorted newest-first by default (`createdAt` desc, page/size otherwise
    following the same `Pageable`/`PageResponse` convention every other paginated list here uses).
*   `POST /api/admin/tasks/{id}/restart`: Resets one task back to `PENDING` (clears
    `errorMessage`/`stackTrace`, sets `executeAt` to now) regardless of its current status —
    including `QUEUED`/`PROCESSING`, unlike `AdminServiceImpl#updateImageTaskToDo`'s own replay
    pass, which skips anything not already `COMPLETED`. An admin manually restarting a task they
    can see in the UI is trusted to know it's actually stuck, not still genuinely running; a real race with a
    still-alive worker just means that task executes twice, which every task type already has to
    tolerate (the same hourly stuck-task reclaim can race a slow-but-alive worker identically, with
    no human judgment involved at all).
*   `POST /api/admin/tasks/restart` (`{taskIds: [...]}`): Bulk counterpart for the Tasks page's
    mass-selection action — one round trip instead of one per row; returns `{restarted: n}`.
*   `POST /api/admin/tasks/{id}/park` / `.../unpark`: Flip a task's status to `PARKED` (pull it out
    of the pending queue without touching `errorMessage`/`payload`/`executeAt`) or back to
    `PENDING`. Unconditional on the task's current status, same trust reasoning as restart above.
*   `POST /api/admin/reprocess/faces` / `.../duplicates`: reset every `ISOLATE_FACES` /
    `CHECK_DUPLICATION` task to `PENDING` in one statement; returns `{tasksReset: n}`.
    `FaceDetectionService`/`DuplicationService` both delete a photo's prior results before
    re-running, so this is a clean re-extraction, not a duplicating one.
*   `POST /api/admin/reprocess/persons`: destructive — deletes every person (+ embedding buckets +
    merge suggestions), detaches every face (`person_id = null`, `status = NEW`), and resets the
    single perpetual `FACE_RECOGNITION` task to run now. Returns `{personsDeleted, facesReset}`.
    The frontend confirms before calling it.

### 5.8. Immich import (per-user, not admin-only — see §6)
*   `GET /api/immich/settings`: Current user's Immich connection (`{baseUrl, connected}` — the
    decrypted API key itself is never returned).
*   `PUT /api/immich/settings`: Set/update the current user's Immich `baseUrl`/`apiKey`
    (`apiKey` blank/omitted keeps the currently stored key, only updates `baseUrl`).
*   `DELETE /api/immich/settings`: Disconnect — clears `baseUrl` and the stored API key.
*   `POST /api/immich/sync-days` / `GET /api/immich/days`: Refresh/read the current user's Immich
    day-index table (`{days: [...], coverage: {...}}`).
*   `GET /api/immich/days/{day}`: Poll one day's import status (`day` = ISO `yyyy-MM-dd`).
*   `POST /api/immich/days/{day}/import`: Trigger (or retry) that day's import.
*   `POST /api/immich/albums/import`: Import Immich albums + album-level shared links
    (run after day-imports — see §6).

### 5.9. Persons & Faces
*   `GET /api/faces/persons`: Paginated list of every person, returning the lean
    `PersonSummaryDTO` (`id`, `name`, `coverPhoto` — no `photoCount`, unlike `PersonDTO` below),
    since `Persons.jsx` only ever renders the cover thumbnail and name for this grid. Backed by
    `PersonRepository.findAllBySpaceIdOrderByNamedFirstThenPhotoCountDesc`, which `left join
    fetch`es `coverFace` to avoid an N+1 lazy load per person. See §4.3's Persons View section for
    why this endpoint used to balloon to tens of MB and several seconds to load (fixed by resizing/
    re-encoding the face thumbnail at detection time, not by this DTO trim, which only shaves a
    few bytes of unused JSON).
*   `GET /api/faces/person/{id}`: A single person by id, as the fuller `PersonDTO` (adds
    `photoCount`) — used by the Person detail page and the merge-suggestion/rename-collision
    pickers, which do render a photo count.
*   `PUT /api/faces/person/{id}`: Rename a person (`{name, confirmed, mergeWithPersonIds}`) —
    409 + candidate `PersonDTO`s on a name collision unless `confirmed: true` (§4.3).
*   `GET /api/faces/person/{id}/faces`: Every `FaceEntity` linked to a person.
*   `GET /api/faces/person/{id}/days` / `/photos/day/{day}`: Day-indexed photos for a person,
    same pattern as Library/Album/Space/Garbage (§4.1).
*   `DELETE /api/faces/person/{personId}/photo/{photoId}`: Unlink a person from one photo
    (§4.3) — does not delete the underlying `FaceEntity` or the photo.
*   `POST /api/faces/persons/merge`: Merge two people (`{personIds: [id1, id2]}`) — the person
    with fewer faces always merges into the one with more (§4.3).
*   `GET /api/faces/persons/merge-suggestions/count`: Cheap count of live "these two people might
    be the same" suggestions for the current user's space (`{count: n}`) — no face thumbnails, not
    even one suggestion's worth. Backs the banner text before the popup is opened.
*   `GET /api/faces/persons/merge-suggestions?page=&size=`: Paginated `PersonMergeSuggestionDTO`
    list, most-similar first (§4.3) — written by an extra step alongside face recognition, not
    computed on request. The popup only ever shows one pair at a time, so the frontend always asks
    for `size=1`; each entry embeds both persons' full base64 cover-face thumbnails, which is why
    this is paginated at all rather than the flat list it used to be (see §4.3's Persons View
    section for the full story on why that mattered).
*   `GET /api/faces/photo/{id}`: Every face detected on a photo (backs the Lightbox's "Faces
    Detected" section).
*   `DELETE /api/faces/{id}`: Delete one face detection (not the photo).
*   `PUT /api/faces/{id}/person`: Rename/reassign one face's person (`{name, confirmMerge}`) —
    409 + the colliding `PersonDTO` unless `confirmMerge: true` (§4.3).
*   `DELETE /api/faces/{id}/person`: Unlink one face from its person, keeping the `FaceEntity`.

## 6. Technical Architecture (Inferred from Code)

*   **Frontend Framework:** React
*   **Routing:** `react-router-dom` is used for all navigation and view management.
*   **State Management:** The application relies heavily on React Hooks (`useState`, `useEffect`, `useCallback`, `useRef`).
*   **Component-Based Architecture:** The UI is broken down into reusable components (e.g., `PhotoGrid`, `Sidebar`, `HeaderWrapper`).
*   **Custom Hooks:** Application logic is encapsulated and reused through a rich set of custom hooks (e.g., `useDayIndexedPhotos`, `useAlbumManagement`, `usePhotoActions`).
*   **API Communication:** The application communicates with a backend server via a RESTful API.
*   **Internationalization (i18n):** The use of `useLocale` and `messages` indicates that the application is designed to be translatable into multiple languages. All user-facing strings should go through `messages` (`i18n.js`, en/fr) — no hardcoded UI text.
*   **Shared presentational components (Album/Space):** Although Albums and Spaces are different
    domain concepts (references vs. ownership — see Section 1), their UI shells were duplicated
    almost verbatim. This was factored out into presentation-only components, while each domain's
    business rules stayed in its own component/hook:
    *   `components/EntityGridDisplay.jsx` — shared card grid (used by `AlbumGridDisplay.jsx` and
        `SpaceGridDisplay.jsx`). Callers supply `renderCover`, `onOpen`, and their own
        selection-toggle logic (e.g. Space's garbage-selection-clear rule stays in
        `SpaceGridDisplay.jsx`, not in the shared component).
    *   `hooks/useEntityById.js` — shared fetch/loading/error logic (used by `AlbumDetail.jsx` and
        `SpaceDetail.jsx`). Each detail component keeps its own not-found/empty message keys.
    *   `components/CreateEntityModal.jsx` — shared name/description creation form
        (`CreateAlbumModal.jsx`/`CreateSpaceModal.jsx` are thin wrappers around it).
    *   `components/AlbumSelectionList.jsx` — shared album-checklist UI used by both
        `AddToAlbumModal.jsx` (add photos to albums) and `RemoveFromAlbumModal.jsx` (remove a
        photo from albums); the two modals keep distinct selection semantics (locked/pre-selected
        items vs. free toggle).
    *   Rule of thumb for future work in this area: only markup/UI-shell duplication should be
        merged; anything touching what an action *means* for Albums vs. Spaces must stay separate.
*   **`App.jsx` structure:** `App.jsx` is the root component wiring routes, auth, and the various
    domain hooks together. Its own concerns are factored into dedicated hooks rather than inlined:
    `hooks/useAuth.js` (authentication state + login/logout), `hooks/useRouteContext.js` (pure
    derivations from the router `location`: `effectivePath`, `isLightboxOpen`, `currentAlbumId`,
    `currentSpaceId`, `fromGarbage`, `selectedMenu`), `hooks/useAlbumManagement.js` and
    `hooks/useSpaceManagement.js` (mirrored shape, one per domain — see the Album/Space rule
    above), plus several smaller hooks per feature area (`useDayIndexedPhotos`, `useDuplicateManagement`,
    `useShareLinkManagement`, etc.). A large amount of individual state (`selectedIds`,
    `selectedAlbumIds`, `selectedSpaceIds`, and their setters) is still passed down as many
    individual props rather than a consolidated object — this is a known, deliberately deferred
    simplification (see `TODO.md`), since collapsing it touches the prop signature of most
    consumer components (`Header`, grids, modals).
*   **Photo storage layout:** originals/thumbnails are stored on disk under
    `{iphoto.storage.photos|thumbnails}/{year}/{month}/` (by `dateTaken`), not one flat directory
    — see `service/PhotoStoragePathResolver.java`, the single owner of this convention. This is
    safe against a later "edit photo date" because `PhotoEntity.originalPath`/`thumbnailPath` are
    stored as full paths and always read back verbatim — nothing reconstructs a path from the
    entity's current date, so an edited date never triggers a file move. Photos stored before this
    layout existed are left flat indefinitely unless an admin runs the optional
    `POST /api/admin/storage/migrate-flat-files` backfill (§5.7) — reads work identically either
    way since paths are explicit per row, not derived from convention.
*   **Immich migration** (per-user, one-time use per account — see `service/immich/`): each user
    connects their own self-hosted Immich server (`/api/immich/settings`, §5.8) and can import its
    assets/metadata/GPS/albums/album-level shared links day-by-day into their own space,
    preserving Immich's own asset UUIDs as iphoto `Photo` ids (`PhotoEntity.id` has no
    `@GeneratedValue`, so this is a direct assignment, same mechanism regular uploads already use
    with a random UUID). People/faces and favorites are deliberately **not** migrated. Architecture:
    *   **Credentials**: `UserEntity.immichBaseUrl` (plain) + `immichApiKeyEncrypted`/
        `immichApiKeyIv` (AES-GCM ciphertext). The AES key is derived (SHA-256) from the user's own
        `password` column — i.e. their **bcrypt hash**, not their raw password (see
        `service/immich/ImmichCredentialCipher`) — a deliberate simplicity trade-off: it's
        obfuscation-at-rest rather than a real password-derived secret, but it means any thread
        that can load the `UserEntity` row (including a background `SYNC_FROM_IMMICH`/
        `UPLOAD_FROM_IMMICH` task with no HTTP session) can decrypt without session-caching or
        "re-enter your password"
        plumbing. `UserService#resetPassword` re-encrypts these two columns whenever the password
        changes (old hash → decrypt → new hash → re-encrypt), so a password change never silently
        breaks the stored key. `ImmichClient` takes credentials as an explicit per-call parameter
        (no shared base-url/api-key bean) since each user's server differs.
    *   `ImmichImportDayEntity` (table `immich_import_days`, unique on `(day, user_id)`) is both
        the per-user day-index the import page renders and the per-day progress tracker
        (`queuedCount` = the day's total known asset count, stable across re-runs;
        `imported/skipped/failedCount` are cumulative atomic counters, incremented from
        concurrently-finishing tasks — see below). A day reaches a terminal `status`
        (`COMPLETED`/`PARTIAL`/`FAILED`) once those three sum to `queuedCount`.
    *   **Day-index sync is month-sourced, not day-sourced**: Immich's `/timeline/buckets` only
        supports `size=MONTH` on current server versions — the `DAY` bucket size that used to
        exist was removed server-side (confirmed against the user's own real server: requesting
        `size=DAY` silently returns month buckets). There's no day-count-only endpoint left, so
        `ImmichImportService.syncDayIndex` fetches every asset in each month bucket (via
        `/search/metadata`, paginated) and groups them locally by each asset's own taken-date to
        build real per-day rows. Heavier than a plain day-count call would be, but it's the only
        way left to get an accurate day-by-day index — per-day *import* itself (`getAssetsForDay`)
        was never affected, since it already used `/search/metadata` with day-boundary
        `takenAfter`/`takenBefore` rather than a day bucket.
    *   **Per-asset ingestion is two chained phases**, not one task, both riding the existing
        `TaskEntity`/`TaskWorkerManager` scheduled-poller infrastructure (the only async-job
        mechanism in the codebase): `TaskType.SYNC_FROM_IMMICH` (phase 1) fetches the asset +
        metadata from Immich and downloads the original into `iphoto.storage.immich-temp` at a
        deterministic `{photoId}.{ext}` path — its final `{year}/{month}` folder is deliberately
        *not* touched yet — then queues one `TaskType.UPLOAD_FROM_IMMICH` task (phase 2) ~1 minute
        later, carrying everything phase 2 needs (staged file path + all metadata) as one JSON
        payload (`model/immich/ImmichPendingUploadPayload`). Phase 2 generates the thumbnail (same
        method a normal upload uses), then commits the `PhotoEntity`/`PhotoMetadataEntity` rows in
        their own **independently-committed transaction** (`ImmichImportService
        .persistPhotoAndMetadata`, `PROPAGATION_REQUIRES_NEW` via an explicit `TransactionTemplate`
        — not `@Transactional`, since that method is called from within the same class and Spring's
        proxy-based `@Transactional` doesn't intercept such same-class calls) and only *after* that
        commit succeeds does it move the staged file into its final folder. This ordering — DB row
        committed before the file is ever placed at its permanent path — is deliberate: the old
        single-phase design moved the file first, so a later DB failure left an orphaned file
        sitting at the final path with no row, and since that `Files.move` used no
        `REPLACE_EXISTING`, every future retry of that same asset failed identically forever
        (`FileAlreadyExistsException`). The two-phase design can't hit that: nothing is ever moved
        into a final path until its row is durably committed elsewhere.
        *   **Resilience/retry**: `triggerDayImport`'s per-asset skip check no longer treats "the
            row exists" as "fully done" — it also requires the row's `originalPath` file to
            actually be on disk (`isFullyImported`), because phase 2 can leave a row committed
            with its file still only staged if the move itself fails right after the commit (e.g.
            a disk error). A retry in that state resumes via `finalizeUploadFromImmich`'s own
            re-entry guard (row already exists → skip re-insert, just finish the move) rather than
            re-inserting. If only phase 2 previously failed (phase 1's staged file still sits in
            immich-temp), a retrigger re-queues phase 2 alone, reusing its failed task's own
            payload verbatim — no need to re-hit Immich for a failure that was never Immich's
            fault. If phase 1 itself failed, a retrigger re-queues a fresh phase 1.
        *   **Space photo count updates are event-driven and atomic, not lock-based**: multiple
            assets' phase-2 tasks for the same space can finish concurrently on different virtual
            threads, all needing to adjust that space's `photoCount`. There's no per-space lock
            (an earlier `SpaceService.lockSpace`/`tryLock`/`SpaceLockingException` mechanism was
            removed) — `SpaceService.increaseSpacePhotoCount`/`decreaseSpacePhotoCount` instead
            publish a `SpacePhotoCountChangedEvent`, handled synchronously (same transaction as the
            triggering photo add/remove) by `SpacePhotoCountListener`, which applies a single
            atomic `UPDATE ... SET photo_count = photo_count + :delta` (`SpaceRepository
            .adjustPhotoCount`) — inherently race-free under concurrent writers to the same row,
            no lock/retry needed. Every place a photo is added to or removed from a space (upload,
            Immich import, move to/from garbage, move to another space) goes through this same
            path.
    *   Albums/shares don't have Immich's UUID as their iphoto id (both are `@GeneratedValue`), so
        they instead get a new nullable `externalSource`/`externalId` pair (+ unique constraint,
        still global not per-user — collision across two different users' Immich servers is
        judged negligible, real Immich UUIDs) for idempotent find-or-create on re-import,
        bypassing `AlbumService.createAlbum`'s/`ShareService.createShare`'s normal name-collision
        throw. Album import must run *after* day-imports (`AlbumService.addPhotosToAlbum` requires
        the photo to already exist in a MAIN space); the import page surfaces this ordering as a
        hint rather than enforcing it structurally. An Immich shared link pointing at individual
        assets (not a whole album) has no iphoto equivalent (iphoto shares are album-only) and is
        skipped with a warning, not mis-mapped.
    *   **Coverage check**: even with the month-sourced sync above, `/search/metadata` still keys
        off a determinable date, so an asset with no usable date could still be missed — added
        after the user noted Immich will be *deleted* once migration finishes. `GET
        /api/immich/days` returns Immich's true total asset count (`/assets/statistics`) alongside
        the sum of known day-bucket counts, and the import page shows a warning banner if they
        don't match — meant to be resolved before deletion.
    *   **Caveat carried over from `TODO.md`**: several Immich response shapes in
        `model/immich/*DTO` (`exifInfo` field names, the per-day/per-month asset-listing endpoint)
        were built from Immich's public API docs without full field-by-field live verification,
        and are flagged in code comments accordingly — treat `ImmichClient` as the first thing to
        check if an import behaves oddly.
*   **Database schema:** Flyway-managed (`org.flywaydb:flyway-core` +
    `flyway-database-postgresql`), versioned migrations under
    `src/main/resources/db/migration/` (`V1__baseline_schema.sql` is the current baseline).
    `spring.jpa.hibernate.ddl-auto` is `validate`, not `update` — Hibernate checks the schema
    matches its entity mappings at startup but never creates/alters tables itself; every schema
    change needs a new Flyway migration. Every entity's `@Table(indexes = ...)`/
    `@UniqueConstraint(name = ...)` annotations are kept mirroring the migration's actual
    constraint/index names (`uk_<table>_<cols>`/`idx_<table>_<cols>`/`ck_<table>_<col>`) — purely
    for `ddl-auto: validate` and readability, since Hibernate doesn't check names, only that the
    columns/tables it expects exist.
*   **Backend testing:** JUnit/MockMvc integration tests under `src/test/java/org/iphoto/`, one
    per service interface, run against an in-memory H2 database (`MODE=PostgreSQL`) rather than a
    real Postgres — H2 is fast enough to share one Spring context/database across the whole test
    run, isolated by unique per-test names/ids rather than a fresh database per class.
    `FaceRecognitionService`/`DuplicationService`/`FaceDetectionService` are deliberately excluded
    (native/model-dependent, no live face-detection model available in every environment that runs
    these tests) — `FaceDetectionConfiguration`'s model-loading beans are `@Profile("!test")`, with
    a no-op `TestFaceDetectionService` standing in so `TaskWorkerManager`'s constructor dependency
    is still satisfied.
*   **Frontend testing:** Vitest + React Testing Library (`npm test`). No CI workflow runs this
    automatically yet — it's a local-only `npm run` command for now. Test files live next to
    the code they cover (`*.test.js`/`*.test.jsx`), not in a separate `__tests__` directory.
    Shared test helpers live in `src/test/` (`setup.js` for global test environment config —
    jest-dom matchers, an `IntersectionObserver` polyfill since jsdom doesn't implement it;
    `testUtils.jsx` for a `localeWrapper` needed by anything using `useLocale()`). Coverage is
    intentionally partial so far — see `TODO.md` for what's covered and what's a deliberate gap.
*   See `TODO.md` in this directory for the running list of in-progress/planned refactor work.
