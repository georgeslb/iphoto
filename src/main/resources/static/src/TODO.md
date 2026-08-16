# Refactor TODO

Running list of refactor/cleanup work on the frontend (`static/src`). Update this file whenever
new work is identified or completed, so nothing gets lost between sessions.

## Done

- [x] **Bug fix**: `App.jsx` called
      `useAppNavigationAndSelection(photos, null, backgroundLocation, effectivePath, null)` —
      the 2nd (`currentDuplicateGroup`) and 5th (`saveScrollPosition`) arguments were hardcoded to
      `null` even though both values already existed in `App.jsx` (from `useDuplicateManagement()`
      and `useScrollRestoration()` respectively). Effect: `handlePhotoClick`'s
      `if (saveScrollPosition) saveScrollPosition();` was silently a no-op, so the scroll position
      was never saved before opening the Lightbox — meaning the "restore previous scroll position
      after returning from a photo" behavior (see specification.md §4.1) was completely
      non-functional. Also, hard-refreshing a `/duplicates/.../asset/:photoId` URL couldn't
      resolve the photo since `currentDuplicateGroup` was unavailable. Fixed by reordering the
      hook calls in `App.jsx` (moved `useScrollRestoration`/`useDuplicateManagement` above
      `useAppNavigationAndSelection`) and passing the real values through. Note: earlier in this
      same session, `saveScrollPosition` was incorrectly flagged and removed as an "unused
      destructured value" from `App.jsx` — that was wrong; it wasn't dead, it was disconnected.
      Restored and wired correctly here.
- [x] Removed leftover debug `console.log` statements from `hooks/useScrollRestoration.js` and
      `hooks/usePhotoData.js` (found while fixing the bug above). `console.error` calls for real
      failures were left in place.
- [x] Extracted `components/EntityGridDisplay.jsx` — shared card-grid shell used by
      `AlbumGridDisplay.jsx` and `SpaceGridDisplay.jsx`. Domain rules (garbage selection-clear,
      nav targets, cover rendering) stayed in each caller.
- [x] Added `hooks/useEntityById.js` — shared fetch/loading/error hook used by
      `AlbumDetail.jsx` and `SpaceDetail.jsx`. Detail components kept separate (different
      not-found/empty message keys, different semantics per spec).
- [x] Added `components/CreateEntityModal.jsx` — shared name/description form.
      `CreateAlbumModal.jsx`/`CreateSpaceModal.jsx` are now thin wrappers with unchanged prop
      signatures (no `App.jsx` changes needed).
- [x] Added `components/AlbumSelectionList.jsx` — shared album-list-with-checkbox UI used by
      `AddToAlbumModal.jsx` and `RemoveFromAlbumModal.jsx`.
- [x] Fixed `RemoveFromAlbumModal.jsx`: it was the only modal not using `useLocale` (hardcoded
      English strings). Added missing `removeFromAlbumModalTitle`, `removeFromAlbumModalDescription`,
      `photoNotInAnyAlbums`, `removeFromSelectedAlbumsButton`, `selectAtLeastOneAlbumToRemove` keys
      to `i18n.js` (en + fr). Also replaced a blocking `alert()` with the same inline
      `error-message` pattern used elsewhere in the app.
- [x] Removed unused prop `setSelectedIds` from `AddToAlbumModal.jsx` (never passed by `App.jsx`,
      never used in the component body).
- [x] Deleted `hooks/usePhotoSelection.js` — fully dead file, superseded by `useMenuSelection.js`
      (import and call site were both commented out in `App.jsx`). Also removed the now-pointless
      `menuSelection`/`setMenuSelection` state in `App.jsx` that only fed `usePhotoSelection`;
      inlined its default shape directly into `photoSelection`'s initial state.
- [x] Removed a leftover `console.log(menuAlbumId)` debug statement in `Header.jsx`.
- [x] **Bug fix** in `HeaderWrapper.jsx`: `handleRemovePhotosFromAlbum` was wrapped as
      `() => handleRemovePhotosFromAlbum(albumId)`, referencing an `albumId` that was never
      destructured in scope (only `menuAlbumId` was) — this threw a `ReferenceError` any time a
      user clicked "Remove from Album" in the Header (the bulk-action button, not the Lightbox's
      `RemoveFromAlbumModal`, which is a separate working code path). Fixed by passing the handler
      straight through, matching how `Header.jsx` already calls it
      (`onClick={() => handleRemovePhotosFromAlbum(menuAlbumId)}`) and how every other handler in
      `HeaderWrapper.jsx` is wired. Also removed dead props `photos`, `currentDuplicateGroup`,
      `spaceId`, `albumId` that `App.jsx` was passing into `HeaderWrapper` but that were never
      used/destructured there, and simplified a pointless identity-wrapper around
      `setShowCreateShareLinkModal`.
- [x] `App.jsx` shrunk from 653 → 499 lines (~24%), pure extraction/dedup, no behavior change:
      - Added `hooks/useSpaceManagement.js`, mirroring `useAlbumManagement.js`'s shape — moved
        `spaces`, `garbageSpaces`, `newSpaceName`/`Description`, `spaceCreationError`,
        `showCreateSpaceModal`, `fetchSpaces`, `fetchGarbageSpaces`, `handleCreateSpace`,
        `handleDeleteSpaces` out of `App.jsx` (this logic had never been extracted, unlike the
        equivalent Album logic — that asymmetry was the main source of `App.jsx`'s bulk). Also
        dropped `currentSpaceDetails`/`isAlbumAllowedInCurrentSpace`, which were computed but
        never actually consumed anywhere (dead derived state).
      - Added `hooks/useAuth.js` — `isAuthenticated`, `userRole`, `loadingAuth`,
        `checkAuthStatus`, `handleLogout`. Pure move, same logic/order.
      - Added `hooks/useRouteContext.js` — pure derivations from `location`
        (`effectivePath`, `isLightboxOpen`, `currentAlbumId`, `currentSpaceId`, `fromGarbage`,
        `selectedMenu`). No hooks used internally, just derivation; kept the `use` prefix for
        convention since it's called like the others at the top of `App.jsx`.
      - Deduped the 3 `PhotoGrid` route elements and the 2 `AlbumDetail` route elements into
        shared JSX variables (`libraryPhotoGrid`, `albumDetailElement`) reused across their
        `<Route>` entries — same route paths/count as before. (Considered collapsing
        `/albums/:albumId` + `/albums/:albumId/asset/:photoId` into one route via React Router's
        optional-segment syntax, but backed off — couldn't browser-verify the exact matching
        semantics and this path matters for anonymous share-link viewing, so kept two explicit
        routes rather than risk it.)
      - Removed several destructured values that were never used anywhere in `App.jsx` beyond
        their own destructuring line (dead reads from otherwise-live hooks, not dead hooks
        themselves): `setPhotos`, `page` (from `usePhotoData`), `saveScrollPosition` (from
        `useScrollRestoration` — hook is still called for its side effect, just not destructured),
        `setCurrentDuplicateGroup`, `setIsLastDuplicateGroup` (from `useDuplicateManagement`),
        `setAlbums`, `setAlbumCreationError` (from `useAlbumManagement`), `setShareLinkCreationError`
        (from `useShareLinkManagement`).
- [x] **Bug fix**: upload status was getting stuck on "Pending" forever. In
      `components/UploadManager.jsx`, `pollTaskStatus`'s polling `setInterval` treated `PENDING`
      as a terminal status alongside `COMPLETED`/`FAILED`/`IGNORED`/`TRASHED` and called
      `clearInterval` on it, so once a task hit `PENDING`, polling stopped permanently and the UI
      showed "Pending" until the user manually reloaded. Fixed by splitting "update the visible
      status" from "stop polling": `PENDING` now still updates the UI label but does not clear
      the interval, so polling continues automatically every 3s until a real terminal status
      (`COMPLETED`/`FAILED`/`IGNORED`/`TRASHED`) is reached.
- [x] **Feature**: shift+click range selection in `components/PhotoGrid.jsx`. Added a
      `photoIndexById` map (id → index in the flat `photos` array, memoized) and a
      `lastClickedIndexRef` anchor. Shift+click (on the card or the small select-overlay
      checkbox) now selects every photo between the anchor and the clicked photo (inclusive,
      added to the existing selection); a plain click still toggles single selection / opens the
      Lightbox as before, and also updates the anchor. Applies uniformly everywhere `PhotoGrid` is
      used (Library, Garbage, Album detail, Space detail) since it's implemented once, shared.
      Not extended to `DuplicatePhotoGrid.jsx`'s separate selection UI (small per-group lists,
      range-select adds little value there) — can revisit if wanted.
- [x] **Bug fix (backend)**: album detail pagination ("second page not fetching") was caused by
      `AlbumController.getAlbumPhotos` (`src/main/java/org/iphoto/controller/AlbumController.java`)
      returning a plain `List<Photo>` instead of paginated data, unlike `/api/photos`
      (`PageResponse<Photo>`) and `/api/spaces/{id}/photos` (`Page<Photo>`). In
      `hooks/usePhotoData.js`, `isLast` is forced to `true` whenever the response is a bare array
      (`Array.isArray(data) ? true : ...`), so `hasMore` went false after the first page and
      `PhotoGrid`'s infinite-scroll observer never fired again for albums. **Fixed on the backend**
      by the user directly (not by me) — frontend needed no changes since `usePhotoData.js`
      already branches correctly on the response shape.
      - While tracing this, found and deleted two more fully-dead files, same situation as
        `usePhotoSelection.js` earlier: `hooks/useRefreshAndScroll.js` and `hooks/useRefreshLogic.js`
        — both were superseded, unreferenced-anywhere earlier implementations of the
        `refreshAndMaintainScroll` logic that now lives inline in `App.jsx` as
        `actualRefreshAndMaintainScroll`.
      - Also explained (not a bug): duplicate/doubled API requests seen in dev are
        `React.StrictMode` (enabled in `main.jsx`) double-invoking effects — standard React 18 dev
        behavior, does not happen in the production build.
- [x] **Set up ESLint config**: `package.json`'s `lint` script (`eslint . --ext js,jsx
      --report-unused-disable-directives --max-warnings 0`) previously failed outright with
      "couldn't find a configuration file" — all the needed plugins (`eslint-plugin-react`,
      `-react-hooks`, `-react-refresh`) were already in `devDependencies` but never wired up.
      Added `.eslintrc.cjs` (legacy config format — ESLint 8.57 is installed, not 9, so no flat
      config) based on the standard Vite React template: `eslint:recommended` +
      `plugin:react/recommended` + `plugin:react/jsx-runtime` + `plugin:react-hooks/recommended`
      + `react-refresh`. Set `no-unused-vars` to warn with `varsIgnorePattern: '^React$'` since
      every file does `import React from 'react'` (harmless under the modern JSX transform, but
      not needed) — without that, ~30 files would each report a false-positive "React is unused".
      `npm run lint` now runs and finds **23 real problems (11 errors, 12 warnings)** — see the
      new entries below, not fixed yet.
- [x] **Correctness bug fix, `Lightbox.jsx`**: ESLint's `react-hooks/rules-of-hooks` flagged 9
      errors (not 7 as first estimated) — `if (!selectedPhoto) return null;` sat in the middle of
      the component, before 9 `useCallback`/`useEffect` declarations (`clampPan`, `handleWheel`,
      `handleMouseDown`, `handleMouseMove`, `handleMouseUp`, `handleMouseLeave`, the keydown
      listener effect, `startImageEditing`, the image-editor-init effect), so those hooks were
      skipped entirely whenever `selectedPhoto` was falsy — a real hook-count mismatch between
      renders. In practice this was latent rather than actively crashing, since `Lightbox` is only
      ever mounted via `LightboxWrapper.jsx`, which already guarantees `selectedPhoto` is truthy
      before rendering `<Lightbox>` at all (verified `Lightbox` has no other call site) — but it
      was still a real landmine for any future caller. Fixed by moving the early return to just
      before the JSX (after all hooks), and guarding the one unconditional dereference between
      them: `currentIndex = selectedPhoto ? photos.findIndex(...) : -1`. Down to 0 errors, 3
      pre-existing warnings left in this file (unused `Users` import, unused `albumAllowed` state,
      missing `useEffect` deps on the keydown listener).
      Project-wide: 23 → 14 problems (11 → 2 errors) after this fix.
- [x] **Feature/migration**: replaced `tui-image-editor` with `react-easy-crop` for the Lightbox's
      image editing, scoped to crop/rotate/zoom only (dropped draw/shape/icon/text/filter, which
      `tui-image-editor` had but weren't asked for). Motivation: `react-easy-crop` was already an
      unused dependency in `package.json` (someone had evaluated it and never finished the swap);
      `tui-image-editor` is a large, largely-unmaintained vanilla-JS library that had to be
      awkwardly wrapped in a ref/imperative `useEffect` (which was also the source of the
      rules-of-hooks bug fixed above) rather than used as a real React component.
      - Added `utils/cropImage.js` — canvas-based `getCroppedImageBlob(imageSrc, pixelCrop,
        rotation)`, since `react-easy-crop` only reports crop geometry and leaves producing the
        final image to the consumer (standard recipe for this library).
      - `Lightbox.jsx`: removed `imageEditorRef`/`editorInstance` refs, `isApplyButtonVisible`
        state, the TUI init/`MutationObserver` effect, and the TUI CSS imports/inline style rule.
        Added `editCrop`/`editZoom`/`editRotation`/`editCroppedAreaPixels` state, reset together
        whenever the photo changes or editing is cancelled/saved. Rotate is two 90°-increment
        buttons (`RotateCcw`/`RotateCw`) rather than TUI's free-rotate handle — matches the
        "rotate" part of the ask without extra UI complexity; can add a fine-rotation slider later
        if wanted. Also removed the unused `Users` icon import while touching this file's imports
        (the unused `albumAllowed` state was left as-is — unrelated, already tracked below).
      - **Requested behavior**: "Save"/"Save as New" now only render when
        `hasEditChanges` (`zoom !== 1 || rotation !== 0 || crop.x !== 0 || crop.y !== 0`) is true;
        "Cancel" always renders while editing so the user can always exit.
      - Ran `npm uninstall tui-image-editor` (verified zero remaining source references first;
        removed 127 transitive packages including `tui-color-picker`). Left `react-cropper`,
        `react-advanced-cropper`, `react-photo-album` alone — still unused, but swapping/removing
        those wasn't part of this task; see open item below.
      - Bonus effect: production bundle dropped from ~1,162 KB to ~464 KB JS (gzip 332→139 KB) and
        CSS from ~75 KB to ~31 KB, and the persistent `tui-color-picker`/`tui-image-editor` CSS
        build warnings (the `backbround-color` typo, IE `*display` hacks) that showed up on every
        single build this whole session are gone, since that CSS was bundled from those packages.
- [x] **Feature/migration**: replaced `react-easy-crop` with `react-cropper` (`cropperjs`) for the
      Lightbox's image editor. Motivation: `react-easy-crop`'s crop box is a fixed-size,
      fixed-aspect (default 4:3) window — you can't drag/resize it, the only way to "select a
      smaller area" is to zoom the image behind it. User explicitly wanted "desktop crop tool"
      behavior — a real draggable/resizable rectangle, like Cropper.js — while keeping export
      quality. `react-cropper` was already sitting in `package.json` unused (see the open item
      above, now resolved for this package specifically).
      - `Lightbox.jsx`: removed `editCrop`/`editZoom`/`editRotation`/`editCroppedAreaPixels` state
        and `hasEditChanges` entirely — cropperjs owns crop/zoom/rotation state internally via its
        instance (`cropperRef.current.cropper`), and since `<Cropper>` unmounts whenever
        `isEditingImage` flips false, that internal state resets for free on cancel/save/photo
        change (no manual reset code needed, unlike the previous controlled-state approach).
      - `dragMode="crop"` (Cropper.js default) + `cropBoxResizable`/`cropBoxMovable` (both default
        true) is what gives the actual "drag to draw/resize a rectangle" behavior that was asked
        for; `autoCropArea={1}` starts the box covering the whole image so nothing looks
        pre-cropped when the editor opens. `viewMode={1}` keeps the crop box from being dragged
        outside the image canvas.
      - Rotate is still the same two 90°-increment buttons, now calling `cropper.rotate(±90)`
        imperatively instead of driving a `rotation` prop. Replaced the old zoom range-input with
        `ZoomIn`/`ZoomOut` buttons calling `cropper.zoom(±0.1)` (wheel/pinch zoom also works, since
        `zoomable` defaults to true) — needed something clickable for trackpad/no-wheel users now
        that there's no slider.
      - **Behavior change**: "Save"/"Save as New"/"Cancel" are now all shown as soon as editing
        starts — dropped the old `hasEditChanges` gate. Cropper.js always has an active crop box
        (even untouched, at `autoCropArea={1}` it's the full image), so "did the user change
        anything" isn't a clean yes/no the way it was with `react-easy-crop`'s neutral
        zoom=1/crop={0,0} defaults; redoing that check would mean diffing crop-box geometry against
        the initial `ready` state, which felt like solving a problem the new library doesn't
        actually have (most desktop crop tools just always show Apply/OK). Flag if this needs to
        come back.
      - Deleted `utils/cropImage.js` (`getCroppedImageBlob`) — no longer needed.
        `cropper.getCroppedCanvas({ imageSmoothingEnabled: true, imageSmoothingQuality: 'high' })`
        replaces it, and does so more simply: it reads crop box geometry against the *original*
        image data internally, so the exported canvas is already at full source resolution (this
        is what satisfies "keep quality") without this app needing to hand-roll the
        rotate-into-a-canvas-then-extract-the-crop-rectangle math the old utility did. Still export
        via `canvas.toBlob(..., 'image/jpeg', 1)` (max quality), matching the previous behavior.
      - Ran `npm uninstall react-easy-crop`. Left `react-advanced-cropper`, `react-photo-album`
        alone — still unused, tangential to this task; see open item above.
- [x] **Bug fix, `hooks/useMenuSelection.js`**: `/api/photos/selected` was always sent
      `photosId: selectedIds` (the grid multi-selection array), so when viewing a single photo in
      the Lightbox with nothing checkbox-selected in the grid (the common case), `photosId` was
      sent as `[]` — the backend had no way to know which photo was being viewed, so the returned
      per-photo capability flags (`canBeRestored`, `canBeSetAsCover`, etc.) for the Lightbox were
      wrong. `selectedPhoto` was already threaded through as a parameter (even listed in the
      `useCallback` dependency array) but never actually read in the function body — a regression
      from when `usePhotoSelection.js` (deleted earlier this session as dead code) was replaced by
      this hook; the old hook had `const ids = selectedPhoto ? [selectedPhoto.id] : selectedIds;`
      and that logic was dropped in the replacement. Fixed: `photosId: selectedPhoto ?
      [selectedPhoto.id] : selectedIds`.
- [x] Added `setPhotoSelection` to the dependency array in `hooks/useMenuSelection.js`'s
      `useCallback` — silences the `react-hooks/exhaustive-deps` warning. No behavior change:
      it's a `useState` setter, which React guarantees has stable identity across renders.
- [x] Bumped JPEG export quality in `utils/cropImage.js`'s `getCroppedImageBlob` from `0.95` to
      `1` (max), matching what `tui-image-editor` used before (`quality: 1`). Note: the live
      on-screen zoom preview in the crop/rotate/zoom editor can look soft at higher zoom levels
      (react-easy-crop fits the image into the editor pane and zooms via CSS transform, so you're
      magnifying an already-downrasterized on-screen image past a point — normal for this class of
      tool) — this is a preview-only effect and was already independent of the exported file's
      quality (the export always re-reads the photo at full native resolution via canvas), so this
      quality bump is a real, separate improvement, not a fix for the preview softness itself.
- [x] **CSS audit and fixes** (`App.css`, `components/Login.css`, `components/UserManagement.css`):
      - **Real bug, currently live**: `.error-message` was defined 3 times as an unscoped, global
        class — once each in `App.css`, `Login.css`, `UserManagement.css` — with different colors,
        backgrounds, borders, and radii. Verified in the actual built CSS bundle that all 3 rules
        were present and, per normal cascade rules (equal specificity, last-loaded wins per
        property), `UserManagement.css`'s rule was winning for most properties and `Login.css`'s
        for the rest — meaning **every `.error-message` in the app** (album/space creation errors,
        share link errors, the remove-from-album validation error added earlier this session, etc.)
        was rendering centered, wrong-colored text instead of `App.css`'s intended boxed style.
        Fixed by scoping the two component-specific rules to their container
        (`.login-form .error-message`, `.user-management-container .error-message`), verified
        against the rebuilt CSS bundle. `.form-group` also exists in both `Login.css`/
        `UserManagement.css` but was already safely scoped there via `.create-user-form
        .form-group` (higher specificity) — no fix needed. Confirmed via a full audit that no
        other standalone top-level class name is duplicated across the three files.
      - Removed `.tie-btn-load.tui-image-editor-header-logo { display: none !important; }` — dead
        leftover rule from the `tui-image-editor` removal earlier this session.
      - Fixed `.album-select-item.selected`'s `box-shadow: 0 44px 12px ...` — clearly a typo for
        `0 4px 12px` (matches the identical pattern used for `:hover` and `.remove-selected`
        immediately above/below it); `44px` would have rendered a huge, visibly broken shadow.
      - Removed a dead, no-op CSS rule and its stale "this rule was causing the issue" debugging
        comments around `.album-card .select-overlay` — the hover/selected rule set `opacity: 1`,
        identical to what the base rule already set unconditionally.
      - **Bug, i18n-dependent, found and fixed**: the upload queue's status label
        (`components/UploadManager.jsx`) stored the *translated display text* (e.g. "ALREADY
        IMPORTED") in `item.status`, then derived the CSS class name from it via
        `.replace(/\s/g,'')`. This happened to produce a matching class name in English
        ("ALREADYIMPORTED") but silently broke in French ("DÉJÀ IMPORTÉ" → "DÉJÀIMPORTÉ", never
        matches `.status-text.ALREADYIMPORTED`) — so the red failure/already-imported styling
        never applied for French users. Root cause: conflating "value used for
        logic/styling" with "value shown to the user" in one field. Fixed by keeping
        `item.status` as the raw, locale-independent backend code always (`UPLOADING`,
        `PROCESSING`, `COMPLETED`, `FAILED`, `IGNORED`, `TRASHED`, `PENDING`), added a
        `statusLabels` map in `UploadManager.jsx` for display text, and updated the CSS selectors
        in `App.css` to match the raw codes (`.status-text.IGNORED`, `.status-text.TRASHED`)
        instead of the translated text.
      - **Bonus find while fixing the above**: `COMPLETED`/`FAILED`/`UPLOADING`/`PROCESSING` were
        never localized at all — always shown as raw English words regardless of locale, unlike
        `IGNORED`/`TRASHED`/`PENDING` which were already translated. Added the missing
        `uploading`/`processing`/`completed`/`failed` i18n keys (en + fr).
- [x] **CSS audit, round 2**:
      - **Real bug fixed**: `.btn:disabled`/`.btn-primary:disabled` were defined generically
        (looked app-wide) but only existed via `Lightbox.jsx`'s inline `<style>` tag — meaning
        disabled-button styling (grayed background, reduced opacity, `not-allowed` cursor) only
        applied while the Lightbox happened to be mounted. Every disabled button elsewhere
        (`AddToAlbumModal`'s "Add to Albums" when nothing new selected, `RemoveFromAlbumModal`'s
        confirm button, the Duplicates Prev/Next nav buttons) got plain unstyled browser defaults
        instead. Moved both rules into `App.css` next to `.btn`/`.btn-primary` where they belong;
        kept the genuinely Lightbox-only rules (`.face-box` etc.) in its inline `<style>` tag.
      - **Design inconsistency fixed**: `Login.css` and `UserManagement.css` both hardcoded
        `#007bff`/`#0056b3` (Bootstrap's default blue) for their primary buttons, while the rest
        of the app uses `--primary-color: #007aff` (defined in `App.css`, globally available via
        `:root` regardless of which stylesheet references it) with `#006ee6` as the hover shade —
        two visibly different blues for the same "primary action" role depending on which page you
        were on. Switched both files to `var(--primary-color)` / `#006ee6`. Also converted
        `.retry-button` in `App.css` itself from a hardcoded (already-matching) `#007aff` to
        `var(--primary-color)` and its hover from `#0056b3` to the standard `#006ee6` for full
        consistency.
      - Verified no unused CSS classes in `Login.css`/`UserManagement.css` (cross-checked against
        their components).
      - Noted, not acted on: **zero `@media` queries anywhere in the app** — there is no responsive
        design at all (fixed 260px sidebar, fixed-width modals, etc.). This is a real gap but a
        much bigger scope than a style-bug pass — this was picked up next (see below).
- [x] **Feature: responsive design, full phone support**. Previously zero `@media` queries
      anywhere. Two breakpoints added: `768px` (main tablet/phone layout changes) and `480px`
      (small-phone fine-tuning for photo/album grid density). Changes:
      - `components/Sidebar.jsx`: now a self-contained collapsible drawer on ≤768px — owns its
        own `isOpen` state internally (no prop-signature changes needed elsewhere), renders a
        fixed hamburger toggle button (`Menu`/`X` icon) and a backdrop that closes it on click; a
        `useEffect` on `pathname` auto-closes the drawer on navigation. On >768px it renders
        exactly as before (no toggle/backdrop shown, normal in-flow sidebar).
      - `App.css`: `.sidebar` becomes `position: fixed` + `transform: translateX(-100%)` sliding
        drawer on ≤768px (`.sidebar.open` slides it in); `.sidebar-toggle`/`.sidebar-backdrop` are
        `display:none` above 768px so desktop is untouched.
      - `components/Header.jsx`: added a `header-actions` className to the (previously
        inline-styled-only) button-row div so it could be targeted by the media query —
        `overflow-x: auto` on mobile instead of wrapping, so a header with many active action
        buttons (multi-select mode) scrolls horizontally rather than breaking the sticky header's
        height. Reduced `.header` padding on mobile and reserved left space for the fixed
        hamburger button so they don't overlap the header's own back-button.
      - `.content-body`, `.photo-card`, `.album-grid` gap/sizing reduced on mobile so more fits
        without horizontal scroll; `.day-header`'s sticky `top` offset adjusted to match the
        shorter mobile header height.
      - `Lightbox.jsx`/App.css: `.lightbox-container` switches to `flex-direction: column` on
        mobile — the photo takes the top, `.metadata-sidebar` becomes a full-width panel capped
        at `45vh` below it (still internally scrollable, unchanged from desktop) instead of a
        fixed 360px side panel that would never fit next to a photo on a phone screen.
      - `.upload-queue` becomes a full-width (small-margin) bottom bar on mobile instead of a
        fixed 320px box that would nearly span an entire phone screen anyway.
      - `UserManagement.css`: `.create-user-form`'s two-column grid collapses to one column;
        `.users-table` becomes horizontally scrollable (`display:block; overflow-x:auto`) rather
        than attempting a full table→card transform, which would've been a much bigger,
        separate redesign.
      - `Login.css`: minor padding reduction on very small screens.
      - Added the `toggleMenu` i18n key (en/fr) for the hamburger button's `aria-label`.
      - **Not exhaustively covered, flagged for later**: touch target sizing (some controls like
        `.check-circle` at 22px are below the ~44px touch-target guideline), and the exact
        `.day-header` sticky-offset pixel values on mobile are a best estimate from reading the
        CSS, not verified on a real device/browser — may need small visual tuning after testing.
- [x] **Unrelated bug found while checking `MapView.jsx` for mobile sizing**: `.map-view-container`
      had no CSS rule anywhere, and Leaflet's `<MapContainer>` needs an ancestor with a definite
      height or it renders at 0px height (a well-known Leaflet gotcha) — this looked like it was
      broken/invisible on desktop too, not a responsiveness-specific issue. Fixed by adding
      `.map-view-container { height: 100%; }` and `.map-view-container .leaflet-container {
      height: 100%; width: 100%; }` to `App.css`. The height chain resolves correctly because
      `.main-content` gets a definite height via flexbox stretch from `.app-container`'s
      `height: 100vh`, which is required for the `height:100%` on `.map-view-container` to mean
      anything at all.
- [x] **Set up automated testing (Vitest + React Testing Library)** — there was zero test
      infrastructure before this (no framework, no test files at all). Added:
      - `vitest`, `@testing-library/react`, `@testing-library/jest-dom`,
        `@testing-library/user-event`, `jsdom` as devDependencies. Note: had to pin
        `jsdom@25` — the version npm installs by default (`jsdom@30`) requires Node
        `^22.14.0 || >=24.0.0` and fails outright on this machine's Node 20.19.2
        (`TypeError: webidl.util.markAsUncloneable is not a function`, from `undici`).
        If this project's Node version is upgraded past 22 later, `jsdom` could be
        bumped back to latest, but there's no need to.
      - `vite.config.js`: added a `test` block (`environment: 'jsdom'`, `globals: true`,
        `setupFiles: './src/test/setup.js'`) — Vitest reads config from the existing Vite
        config directly, no separate config file needed.
      - `src/test/setup.js`: imports `@testing-library/jest-dom/vitest` matchers, and
        polyfills `IntersectionObserver` (jsdom doesn't implement it; `PhotoGrid` needs it
        for infinite scroll).
      - `src/test/testUtils.jsx`: a small `localeWrapper` for tests that render anything
        using `useLocale()`.
      - `package.json`: added `"test": "vitest run"` and `"test:watch": "vitest"` scripts.
      - Initial regression-test batch, each directly targeting a real bug found and fixed
        earlier this session (not written speculatively):
        - `hooks/useMenuSelection.test.js` — the `photosId` bug (sends the viewed photo's
          id when the Lightbox is open, falls back to the grid multi-selection otherwise,
          and prefers the viewed photo over a stale grid selection).
        - `hooks/usePhotoData.test.js` — the album pagination-shape bug (`hasMore` derived
          correctly from a paginated `{content, last}` response vs. a bare array being
          treated as always-final-page), plus the general "next page fetches automatically
          via `setPage`" contract from specification.md.
        - `components/PhotoGrid.test.jsx` — the shift+click range-selection feature (anchor
          + range, extending an existing selection, plain click not doing a range, shift+click
          with no prior anchor falling back to a normal single toggle).
        - `hooks/useAppNavigationAndSelection.test.jsx` — the `saveScrollPosition`
          wiring bug (confirms the hook actually calls it when provided, and doesn't throw
          when it's not).
      - All 15 tests pass; `npm run lint`/`npm run build` unaffected (verified the built
        bundle is unaffected — test files aren't part of the app's module graph).
- [x] **Business-logic hardening pass**: tested and reviewed `useAlbumManagement.js`,
      `useSpaceManagement.js`, `useAuth.js` — found and fixed 3 more real bugs this way
      (chasing down existing ESLint warnings rather than guessing where bugs might be):
      - **Bug fix**: `handleAddPhotosToAlbums` and `handleSetCoverPhoto` in
        `useAlbumManagement.js` both listed `fetchAlbums` as a `useCallback` dependency but
        never actually called it (the two lingering `react-hooks/exhaustive-deps`
        `fetchAlbums` warnings from the ESLint setup earlier — investigated rather than
        left alone). Effect: after adding photos to an album or changing its cover photo,
        the local `albums` state (photo counts, cover thumbnail) went stale in the
        `/albums` grid until an unrelated navigation happened to refetch it. Every sibling
        mutation handler (`handleCreateAlbum`, `handleRemovePhotosFromAlbum`,
        `handleDeleteAlbums`) already refreshed correctly — these two didn't. Fixed by
        adding `await fetchAlbums();` to both success paths.
      - **Bug fix**: `handleDeleteSpaces` in `useSpaceManagement.js` fired all DELETE
        requests without checking whether any of them actually succeeded, then
        unconditionally cleared selection and refreshed the list — so a failed delete
        (network hiccup, or a protected space) looked like it silently worked, no error
        surfaced. `handleDeleteAlbums` already had the correct `responses.every(r => r.ok)`
        check; mirrored that pattern here, added the missing
        `oneOrMoreSpacesFailedToDelete` i18n key (en/fr), and switched the sequential
        `for...await` loop to `Promise.all` to match (parallel deletes, same as albums).
      - `useAuth.js` reviewed, no bug found — but noting for the record:
        `handleLogout`'s catch block doesn't clear auth state or navigate on a network
        failure, unlike the success path. Left as-is (debatable UX call, not a clear bug)
        and the test suite documents the *current* behavior rather than changing it, so a
        future decision to change this is deliberate rather than accidental.
      - Added `hooks/useAlbumManagement.test.jsx` (7 tests), `hooks/useSpaceManagement.test.js`
        (8 tests), `hooks/useAuth.test.js` (6 tests) — 21 new tests, all regression-tied to
        either a bug just fixed or an existing correctness check worth locking in (partial
        delete failure, empty-name validation, share-page auth exemption, etc.). Total
        suite: 36 tests across 7 files, all passing; lint dropped from 12 → 10 problems
        (the two `fetchAlbums` warnings are gone, nothing new introduced).
      - **Test-writing gotcha hit and worth remembering**: an early version of the
        `useAlbumManagement` tests passed `overrides.selectedIds ?? ['photo-1']` *inline*
        inside the `renderHook(() => ...)` callback — a fresh array literal on every
        render. Since the hook has an effect depending on `selectedIds` by reference, this
        caused a genuine infinite re-render loop (100%+ CPU, test hung). Fixed by hoisting
        the value to a stable variable *outside* the render callback. Any hook whose deps
        include an array/object prop needs this same care in its tests.
- [x] **Business-logic hardening pass 2**: reviewed and tested `usePhotoActions.js`,
      `useSpaceActions.js`, `useShareLinkManagement.js`. Found and fixed 4 more real bugs:
      - **Bug fix**: `handleEditPhoto` in `usePhotoActions.js` did `if (!editData.saveAsNew)`
        where `editData` is always the `FormData` instance built in `Lightbox.jsx`'s
        `handleSaveEditedImage`. `FormData` doesn't expose its entries as object
        properties — `editData.saveAsNew` was always `undefined`, so `!editData.saveAsNew`
        was always `true`. Effect: the "Save as New" branch (processing alert + close
        Lightbox) was dead code that could **never** run, regardless of whether the user
        clicked "Save" or "Save as New" — clicking "Save as New" always silently behaved
        like a normal in-place save instead. Fixed to read the value correctly:
        `editData.get('saveAsNew') !== 'true'` (FormData stringifies appended booleans).
        Also removed a `console.log(editData)` debug leftover (logging a `FormData`
        object to console isn't even useful — browsers don't expand its entries by
        default).
      - **Bug fix (silent failures)**: `handleMoveToGarbage`, `handleRestore`,
        `handlePermanentDelete` (`usePhotoActions.js`) and `handleRestoreSpaces`,
        `handlePermanentDeleteSpaces` (`useSpaceActions.js`) all had zero feedback when
        the request failed — no alert, no `console.error`, nothing. A failed action just
        looked like the button silently did nothing. `handleUpdateDate`/`handleEditPhoto`
        in the same file already alerted on failure — these five didn't. Added matching
        `alert(messages[...])` calls to all five, added the missing
        `failedToMoveToGarbage`/`failedToRestore`/`failedToPermanentlyDelete` i18n keys
        (en/fr; the restore/permanent-delete keys are reused by both photos and spaces
        since the wording isn't type-specific).
      - **Bug fix (visible)**: the share-link creation success alert literally showed the
        raw album UUID to the user — `Share link "a1b2c3-...(uuid)"/"my-link" created
        successfully!` — instead of anything meaningful. Root cause: only the album *id*
        was ever threaded into `useShareLinkManagement.js` (from `Header.jsx`'s
        `onClick={() => setShowCreateShareLinkModal(menuAlbumId)}`), never its name.
        Rather than threading the album name through multiple components just to fix a
        cosmetic string, simplified the message to drop the id entirely — the user
        already knows which album they were sharing, since they triggered this from
        inside it; the link *name* they just typed is the only genuinely new information
        worth confirming.
      - **Cleanup**: `useSpaceActions.js` took an unused `selectedIds` parameter (only
        `setSelectedIds` was ever used in the function body) — removed it and updated the
        one call site in `App.jsx`.
      - Added `hooks/usePhotoActions.test.js` (14 tests, including the `saveAsNew`
        regression), `hooks/useSpaceActions.test.js` (5 tests), and
        `hooks/useShareLinkManagement.test.js` (8 tests) — 27 new tests. Total suite: 59
        tests across 10 files, all passing; lint unchanged at 10 pre-existing problems.

## Open / Not Yet Done

- [ ] More regression tests worth adding, deliberately left out of the initial batch to keep
      it focused on what was quick to do well rather than rushed:
      - `UploadManager.jsx`'s "PENDING keeps polling instead of getting stuck" fix — needs
        fake timers (`vi.useFakeTimers()`) plus mocking the chunked-upload flow before
        polling even starts; meaningfully more setup than the other four tests here.
      - `Lightbox.jsx`'s hook-order fix — better suited to an ESLint regression (already
        covered: `react-hooks/rules-of-hooks` will fail CI/lint again if this regresses)
        than a runtime test, but a basic "renders without crashing when selectedPhoto is
        set/unset" smoke test would still be cheap insurance.
      - A root `App.jsx` smoke test (renders without crashing, routes correctly) would catch
        the *class* of bug behind the `saveScrollPosition`/`currentDuplicateGroup` wiring
        issue at the actual call site instead of just the hook's own contract — but needs
        mocking every endpoint that fires on mount (`/api/user/me`, `/api/photos`,
        `/api/photos/selected`, `/api/albums`, `/api/spaces`, etc.), a bigger lift than the
        focused hook tests done here.
      - No CI workflow runs `npm test`/`npm run lint`/`npm run build` automatically yet
        (checked: no `.github/workflows` or equivalent in this repo) — tests only run when
        someone remembers to run `npm test` locally. Worth wiring up if this matters.
- [ ] Responsive follow-up: verify on a real phone/emulator (not just read-through) — the
      `.day-header` sticky offset on mobile and the drawer/backdrop interaction in particular are
      best-estimate values that weren't visually confirmed. Also consider bumping small touch
      targets (`.check-circle` at 22px, etc.) closer to the ~44px guideline if this gets real
      mobile usage.
- [ ] `react-advanced-cropper`, `react-photo-album` are confirmed unused anywhere in `src/` —
      candidates for `npm uninstall` if there's no near-term plan to use them (`react-cropper` was
      in this same boat but is now actually used, see the `react-easy-crop` → `react-cropper`
      migration below).
- [ ] **Cleanup, `Lightbox.jsx`**: unused `albumAllowed` state (`setAlbumAllowed` is called but
      the value is never read — dead state, possibly leftover from a removed feature; worth
      checking git history before just deleting it). The unused `Users` import noted alongside
      this earlier has since been removed as part of the crop-editor migration above.
- [ ] **Cosmetic, `CreateShareLinkModal.jsx`**: 2 `react/no-unescaped-entities` errors — literal
      `"` characters in JSX text around line 24, should use `&quot;`/`&rdquo;` or similar.
- [ ] **Possible stale-closure bugs** (`react-hooks/exhaustive-deps` warnings, not yet
      investigated/fixed):
      - `hooks/useMenuSelection.js:55` — `useCallback` missing `setPhotoSelection` dependency.
      - `App.jsx:195` — `useCallback` missing `currentAlbumId`/`currentSpaceId`/`selectedMenu`.
      - `components/UploadManager.jsx:140` — `useCallback` has unnecessary deps
        `effectivePath`/`refreshAndMaintainScroll`.
      - `hooks/useAlbumManagement.js:71,180` — `useCallback` has an unnecessary `fetchAlbums` dep.
      - `components/UserManagement.jsx:14` — `useEffect` missing `fetchUsers` dependency.
      - `Lightbox.jsx:344` — `useEffect` missing `handleNext`/`handlePrevious`.
      Each needs individual judgment (some "missing deps" warnings are false positives if the
      omission is intentional) rather than blindly auto-fixing.
- [ ] Minor unused vars/imports flagged by lint, not yet cleaned up: `isDuplicatesPage` in
      `Header.jsx`, `Lock` import in `ShareLinkPage.jsx`.
- [ ] `LocaleContext.jsx` gets a `react-refresh/only-export-components` warning (exports both a
      component and non-component values from the same file) — cosmetic, affects Fast Refresh
      only, not correctness.
- [ ] **Needs backend verification** (not a frontend fix): `ShareLinkPage.jsx`'s non-password-protected
      path calls `navigate('/albums/{id}')` without ever hitting a login/session endpoint, but
      `/albums/:albumId` only renders inside `App.jsx`'s `isAuthenticated` branch, and
      `checkAuthStatus()` redirects any non-`/share`-prefixed path to `/login` when
      `/api/user/me` fails. This only works if the backend grants a session/cookie for
      unprotected share links too (the way the password-protected flow explicitly does via
      `credentials: 'include'`). See specification.md §4.4 for full detail. Confirm with backend
      before assuming this is fine or broken.

- [ ] `App.jsx` is down to 499 lines but still wires a large number of individual props through
      to `HeaderWrapper` and route components (`selectedIds`/`selectedAlbumIds`/`selectedSpaceIds`
      + their setters, passed almost everywhere individually). Consolidating these into a single
      selection hook/object was discussed and deliberately deferred — it touches every consumer
      component's prop signature (`Header`, grids, modals), much higher risk than the extractions
      done so far. Worth a dedicated pass if there's appetite for it.
- [ ] `Header.jsx`/`HeaderWrapper.jsx` — the ReferenceError bug is fixed and dead props removed;
      not yet audited for further duplication. Button visibility here is **backend-driven** (see
      specification.md §5/§6) via `POST /api/photos/selected`; do not hardcode capability logic
      client-side when refactoring.
- [ ] `Lightbox.jsx` (520 lines) — not yet reviewed. Large file, candidate for a closer look.
- [ ] `UploadManager.jsx` (193 lines) — not yet reviewed.
- [ ] `useAlbumManagement.js` (209 lines, largest hook) — not yet reviewed for duplication vs.
      space-related hooks (`useSpaceActions.js`, etc.) — remember Album/Space are NOT
      interchangeable domain concepts (references vs. ownership), so any shared logic there
      must be presentation-only, same rule as the grid/detail/modal work above.
- [ ] No ESLint config exists in this project despite `package.json` having a `lint` script that
      references it (`eslint . --ext js,jsx ...` fails with "couldn't find a configuration file").
      Not fixed — flagging since it means lint issues (including future dead code) won't be
      caught automatically until this is set up.
- [ ] Note: a large amount of other files were already modified/uncommitted in the working tree
      before this refactor pass started (`App.jsx`, `Header.jsx`, `HeaderWrapper.jsx`,
      `Lightbox.jsx`, `LightboxWrapper.jsx`, `PhotoGrid.jsx`, `UploadManager.jsx`,
      `useAlbumManagement.js`, `usePhotoData.js`, `ShareLinkPage.jsx`, `ShareLoginPage.jsx`,
      `package.json`/`package-lock.json`, and new-but-uncommitted `useMenuSelection.js`). These
      are pre-existing local work, not part of this refactor — just noting so nothing gets
      confused as unrelated/accidental changes when reviewing the diff.
