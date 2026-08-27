# IPHOTO Application Specification

This document outlines the functionality and behavior of the IPHOTO web application, a comprehensive photo management platform.

## 1. Core Concepts

*   **Photo:** The basic entity in the application. A photo belongs to one and only one **Space** at any given time. Photos have metadata, including date, location, and associated people.
*   **Space:** The primary container for photos. Each photo resides in a single space.
    *   **Main Space:** The default space. Photos in this space are displayed in the "Library" menu. Users can move photos from any other space into the Main space.
*   **Album:** A virtual collection or a "playlist" of photos. An album contains links (references) to photos that exist **in the Main Space only**. A single photo can be included in multiple albums without being moved or duplicated. The visibility of albums (i.e., which albums are displayed to the user) is managed by the backend.
*   **Library:** The view that displays all photos residing in the "Main" space.
*   **Garbage:** A temporary holding place for deleted photos and spaces before permanent deletion.
    It has two distinct sections, both shown on the `/garbage` view:
    *   **Deleted Spaces section:** Spaces that were deleted (soft-delete) from the `/spaces` menu.
        A deleted space behaves like a standard asset: it can be **restored** or **permanently
        deleted**. Permanently deleting a space cascades — all photos still inside it are
        permanently deleted too. The **Main space can never be deleted** (enforced by the
        backend; the frontend does not special-case "Main" itself, it simply respects the
        `canDeleteSpace` capability flag from `/api/photos/selected`, which the backend sets to
        `false` for Main).
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

## 3. Main Application Layout

The application is a Single Page Application (SPA) with a consistent layout:

*   **Sidebar:** A persistent navigation sidebar on the left, providing access to the main sections of the application (Library, Albums, Spaces, Map, etc.) and the logout button. **Responsive:** on screens ≤768px it becomes a collapsible drawer (`components/Sidebar.jsx` owns its own open/closed state) with a fixed hamburger toggle button, closing automatically on navigation.
*   **Header:** A context-aware header that appears above the main content. It displays actions relevant to the current view and selection (e.g., "Delete", "Add to Album", "Upload"). **Button visibility is backend-driven, not computed client-side**: the frontend POSTs the current menu context (`menu`, `spaceIds`, `albumIds` — no separate `lightbox` flag; when the Lightbox is open, `menu` is simply `"lightbox"`, overriding whatever the underlying page's menu would otherwise be) and selected photo IDs to `/api/photos/selected`, and the backend returns capability flags (e.g. `canBeRestored`, `canBeDeleted`, `canBeMovedToMain`, `canBeMovedToAlbum`, `canBeRemovedFromAlbum`, `canBeSetAsCover`, `canBePermanentlyDeleted`) plus a nested `menuSelection` object with menu-level flags (`canUpload`, `canCreateAlbum`, `canDeleteAlbum`, `canCreateSpace`, `canDeleteSpace`, `canRestoreSpace`, `canPermanentlyDeleteSpace`, `canAddAAShareLinkToAlbum`). `Header.jsx` renders buttons purely by reading these flags — it does not decide on its own whether an action is allowed for a given menu/selection combination.
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
    *   **Duplicates is an intentional exception:** `/duplicates` (`DuplicatePhotoGrid.jsx`) shows
        one duplicate *group* at a time for manual comparison/decision, with explicit
        Prev/Next-group buttons rather than infinite scroll — this is a deliberate different UX
        (decide-per-group), not an oversight, and is not expected to follow the same
        pagination/scroll-restoration contract as the other list views.
*   **Inline duplicate grouping:** each `Photo` returned by the photo-listing endpoints can carry a
    `duplicates: Photo[]` field (backend `Photo.java`/`PhotoMapper`, populated via
    `DuplicationService.getDuplicates(PhotoEntity)`) — the other members of its duplicate cluster, if
    any. **Backend status: only `GET /api/photos` (library) and `GET /api/photos/space/{spaceId}`
    populate this today; `GET /api/photos/garbage` (`PhotoService.getGarbagePhotos`) and
    `GET /api/albums/{id}/photos` (`AlbumService.getAlbumPhotos`) do not yet** — not fixed here since
    this was frontend-only scope; the frontend degrades gracefully (`p.duplicates || []`) wherever
    it's absent, it just won't show grouping there until the backend adds it.
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
*   **Upload:** Users can upload new photos. A progress manager (`components/UploadManager.jsx`)
    shows the status of ongoing uploads, chunk-by-chunk; the completion response of
    `POST /api/upload/chunk` includes a `task` object (`{id, status, photoId}` — `photoId` is
    generated up front at task creation, so it's valid immediately, not just once `COMPLETED`),
    which is then polled via `GET /api/tasks/{id}` until the backend processing task finishes.
    Once a queue item has a `photoId` and its status is anything **other than `PENDING` or
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
*   **Actions:**
    *   **Move to Garbage:** Soft-deletes selected photos.
    *   **Move to Main Space:** Moves a photo from its current space to the default "Main" space.
    *   **Restore:** Restores photos from the Garbage back to their original space.
    *   **Permanent Delete:** Permanently removes photos from the system. This action is available for photos in the Garbage.
    *   **Edit Metadata:** Users can modify photo details, such as the date.
*   **Lightbox View:**
    *   Displays a high-resolution version of the photo.
    *   Shows extended metadata (camera, lens, etc.).
    *   Allows navigation between photos in the current collection (e.g., album, search results).
    *   Provides access to individual photo actions (delete, add to album, move to Main Space,
        etc.), each gated by its own `photoSelection` capability flag (e.g. "Move to Main Space"
        only shows when `canBeMovedToMain` is true — same backend-driven pattern as the Header,
        see Section 3).
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
        existing `DuplicateEntity` groups (`findByPhotosIdIn`), all of them are merged into a single
        group (one kept id, `hash` recomputed from the full membership, the other touched groups
        deleted) rather than only ever extending the first candidate. A previously `CLOSED` group is
        reopened to `OPEN` when a new duplicate is folded into it, since it now has a new photo to
        review. This replaced an earlier version of the join condition that was self-contradictory
        (required the existing group to be both a subset of, and larger than, the newly-detected set)
        and was therefore unreachable — every detected duplicate used to spawn its own overlapping
        group instead of joining/merging with existing ones.
*   **Map View:**
    *   A world map (`/map`) displays photos based on their embedded geolocation data.
*   **Persons View:**
    *   A dedicated view (`/persons`) suggests functionality related to facial recognition and grouping photos by people.
    *   Backend pipeline (`FaceDetectionService` + `FaceAligner` + `FaceRecognitionService`): RetinaFace
        (DJL/PyTorch, `FaceDetectionConfiguration`) detects face boxes + 5-point landmarks on the
        **original, full-resolution** photo (not the resized-for-hashing copy `DuplicationService` uses —
        detection needs the extra pixels). Each detected box is padded 20% and, if it clears the size/
        confidence gates below, run through `FaceAligner.align` (rotates level using the eye-line
        landmarks, crops with its own 20% padding, resizes to 112×112 via bicubic interpolation) before
        going through the embedding model. Embeddings + a PNG thumbnail are saved per `FaceEntity`;
        `FaceRecognitionService.groupFaces` clusters them into `PersonEntity`s.
    *   **Small-face gating** (`FaceDetectionService`): RetinaFace's smallest anchor tier (scale=16,
        `FaceDetectionConfiguration`) is built to detect faces well under 60px, but faces under 60px
        (post-padding) used to be discarded outright regardless of detector confidence — a flat
        `w < 60 || h < 60` cutoff — because including them was producing poor-quality embeddings.
        Root cause turned out to be a real bug in `FaceAligner`, not an inherent limit of small faces:
        `leftEye`/`rightEye` were both read from a **freshly-created** `points.iterator()`, so both got
        the same first landmark point, making `rightX-leftX`/`rightY-leftY` always `0` and the eye-line
        rotation a permanent no-op — every face (not just small ones) was aligned unrotated, which hurts
        small faces disproportionately since uncorrected tilt eats a bigger fraction of their little
        available signal. Fixed by reading both points off the same iterator instance. With alignment
        now actually working, the flat cutoff was replaced with `MIN_FACE_SIZE=32` (hard floor — below
        this there's genuinely not enough resolution) plus a confidence-aware band: faces between 32px
        and the old 60px threshold are kept only when the detector's confidence is ≥`0.92` (stricter
        than the model's own `confThresh=0.85`), to control the extra false-positive risk small crops
        carry now that they're no longer blocked outright. Not verified against real photos (no `mvn`/
        live app access from this shell) — worth watching face-detection quality on small/background
        faces after this deploys, and tuning `SMALL_FACE_MIN_CONFIDENCE`/`MIN_FACE_SIZE` from there.
    *   **Age-invariant clustering** (`FaceRecognitionService`): a person's face embedding drifts
        meaningfully over years (e.g. age 5 vs. age 25), so a single flat cosine-similarity threshold
        between two far-apart-in-age photos of the same person will usually never clear the bar
        directly — and shouldn't be loosened to compensate, since that raises the false-merge risk for
        genuinely different people. Instead, both matching paths only ever compare a face against its
        `TEMPORAL_NEIGHBOR_WINDOW` (=10) closest-in-time faces (before AND after), and rely on
        transitive chaining to bridge large age gaps: `findBestMatchForFace` compares a new face
        against each existing `PersonEntity`'s nearest-in-time faces (not that person's whole history
        averaged together, which dilutes the score across eras); `clusterByTemporalProximity` forms
        brand-new people from leftover ungrouped faces by sorting them by photo date and union-find
        (connected-components) merging matches between temporally-adjacent faces — so a childhood and
        an adult photo of the same person can end up in one cluster by chaining through the many
        in-between-age photos of them, without any single pairwise comparison ever needing to bridge
        the full gap itself. This only works as well as the account's photo timeline is populated —
        a person with just two photos taken decades apart, and nothing in between, won't cluster
        together; that's an inherent limitation of this approach, not a bug.
    *   **Known limitation, left alone deliberately**: `MainSpaceAspectConfiguration.enableSpaceFilter`
        skips enabling the per-user `spaceFilter` Hibernate filter for any thread named
        `backend-task*`, and `FACE_RECOGNITION` (like all `TaskWorkerManager` background tasks) always
        runs on such a thread — so `@MainSpaceLimited` queries in `FaceRepository`/`PersonRepository`
        are effectively unfiltered during face grouping, meaning it likely pools faces/persons across
        all users rather than scoping per user. Found while redesigning the clustering logic above;
        the user was asked and explicitly said not to change `MainSpaceAspectConfiguration` for this
        work, so it remains as-is. Worth revisiting if this is ever a multi-tenant concern.
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
        always `null`. Photos come from the pre-existing `GET /api/faces/person/{id}/photos`,
        wired into `usePhotoData`'s path-based routing (`/persons/:id` → `type: 'person'`) the same
        way `/albums/:id` and `/spaces/:id` already were. Opening a photo from here uses the plain
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
delete the link or remove its password at any time. This was substantially rewritten/fixed in a
later pass — the notes below describe the current, working design; see the "History" callout at
the end of this section for what was actually broken beforehand.

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
    3.  Once "ready", `SharedAlbumView.jsx` fetches photos from `GET
        /api/albums/share/{albumId}/photos?page={n}` (paginated, same `Page<Photo>` shape as
        every other photo list) and renders them via the same `PhotoGrid.jsx` used everywhere
        else (read-only here: no selection actions are wired up, just browsing/opening a photo).
        Clicking a photo navigates to `/share/{userMd5}/{name}/p/{photoId}`, which additionally
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
    `getAlbumPhotoForShare` (kept separate from the existing, still-filtered
    `getAlbumPhotos`/`getAlbum`, which the *authenticated* album-detail view still uses — reusing
    the filtered version for guests would 500 them; reusing the unfiltered version for real users
    would remove their space isolation entirely).
*   **History — what was actually broken before this pass** (kept for context, not current
    behavior): the old `/{albumId}/{name}` share-lookup route, the old `ShareAlbumFilter`
    (extracted the wrong URI segment as an id — an off-by-one — and called an
    `@MainSpaceLimited` `shareRepository.findById` that 500'd for guests), `getShareDetailsById`/
    `getSharePassword` (same `@MainSpaceLimited` problem, meaning the password-login endpoint
    itself 500'd for anonymous callers), the unprotected flow never establishing any session at
    all (so every subsequent photo/album fetch was unauthenticated), and a frontend/backend key
    mismatch (`ShareLinkPage.jsx` read `data.selectedAlbumIds`, the backend only ever sent
    `albumIds`/`albumId`). None of this is present anymore, but it's why the guest flow was
    rewritten rather than incrementally patched.

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
*   `PATCH /api/photos/{id}/date`: Updates the date for a single photo.
*   `POST /api/photos/{id}/edit`: Edits a photo.
*   `GET /api/photos/{id}/metadata`: Fetches extended metadata for a single photo.
*   `GET /api/map-assets?path={effectivePath}`: Fetches assets to be displayed on the map view.
*   `POST /api/photos/selected`: Given the current menu context (`menu`, `spaceIds`, `albumIds`)
    and a list of selected photo IDs, returns which actions/buttons should be shown (see Section 3,
    Header). `menu` is `"lightbox"` whenever the Lightbox is open, overriding the underlying
    page's menu (`"library"`/`"album"`/`"space"`/`"garbage"`/etc.) — there is no separate boolean
    flag for it. This is the backend authority for UI capability flags — the frontend does not
    derive these itself.

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
*   `GET /api/albums/share/{albumId}/photos/{photoId}`: A single photo, only if it belongs to that album (backs the shared Lightbox).
*   `GET /api/photos/{id}/thumbnail` / `/original` / `/stream`: Same asset endpoints real users use — guests can only reach the photo their `ShareAccessFilter`-checked album contains.

## 6. Technical Architecture (Inferred from Code)

*   **Frontend Framework:** React
*   **Routing:** `react-router-dom` is used for all navigation and view management.
*   **State Management:** The application relies heavily on React Hooks (`useState`, `useEffect`, `useCallback`, `useRef`).
*   **Component-Based Architecture:** The UI is broken down into reusable components (e.g., `PhotoGrid`, `Sidebar`, `HeaderWrapper`).
*   **Custom Hooks:** Application logic is encapsulated and reused through a rich set of custom hooks (e.g., `usePhotoData`, `useAlbumManagement`, `usePhotoActions`).
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
    above), plus several smaller hooks per feature area (`usePhotoData`, `useDuplicateManagement`,
    `useShareLinkManagement`, etc.). A large amount of individual state (`selectedIds`,
    `selectedAlbumIds`, `selectedSpaceIds`, and their setters) is still passed down as many
    individual props rather than a consolidated object — this is a known, deliberately deferred
    simplification (see `TODO.md`), since collapsing it touches the prop signature of most
    consumer components (`Header`, grids, modals).
*   **Testing:** Vitest + React Testing Library (`npm test`). No CI workflow runs this
    automatically yet — it's a local-only `npm run` command for now. Test files live next to
    the code they cover (`*.test.js`/`*.test.jsx`), not in a separate `__tests__` directory.
    Shared test helpers live in `src/test/` (`setup.js` for global test environment config —
    jest-dom matchers, an `IntersectionObserver` polyfill since jsdom doesn't implement it;
    `testUtils.jsx` for a `localeWrapper` needed by anything using `useLocale()`). Coverage is
    intentionally partial so far — see `TODO.md` for what's covered and what's a deliberate gap.
*   See `TODO.md` in this directory for the running list of in-progress/planned refactor work.
