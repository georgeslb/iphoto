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
      `() => handleRemovePhotosFromAlbum(albumId)`, referencing an `albumIds` that was never
      destructured in scope (only `menuAlbumId` was) — this threw a `ReferenceError` any time a
      user clicked "Remove from Album" in the Header (the bulk-action button, not the Lightbox's
      `RemoveFromAlbumModal`, which is a separate working code path). Fixed by passing the handler
      straight through, matching how `Header.jsx` already calls it
      (`onClick={() => handleRemovePhotosFromAlbum(menuAlbumId)}`) and how every other handler in
      `HeaderWrapper.jsx` is wired. Also removed dead props `photos`, `currentDuplicateGroup`,
      `spaceIds`, `albumIds` that `App.jsx` was passing into `HeaderWrapper` but that were never
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
- [x] **Bug fix, `components/UploadManager.jsx`**: when client-side image/video type
      filtering was added (see the "contentType" migration above), rejected files were
      queued with `file: null`. The Retry button only renders when
      `item.status === 'FAILED' && item.file`, so a client-rejected upload silently lost
      the Retry option that every other failed upload (network/server errors) still has —
      a real regression from before that filtering existed, when an unsupported file was
      rejected server-side instead and always kept its `file`/retry. Fixed by keeping the
      real `File` object (`file: file` instead of `file: null`) on rejected-item queue
      entries; retrying a genuinely unsupported file just re-runs the same client-side
      check and fails the same way again, which is fine — no different from retrying any
      other failure that's expected to fail again. Added `components/UploadManager.test.jsx`
      (2 tests): one regression test asserting Retry renders after a type rejection
      (verified it fails against the pre-fix code), and one covering the existing
      genuine-server-failure retry path end-to-end (click Retry → fetch called again).
      Total suite: 61 tests across 11 files.
- [x] **Feature**: after an upload finishes, a "Show" link appears on that item in the
      upload queue (`components/UploadManager.jsx`). Clicking it opens a lightweight
      preview popup of the new asset — `components/UploadPreviewModal.jsx`, "same as
      Lightbox but without the sidecar": reuses the `.lightbox-overlay`/
      `.lightbox-container`/`.lightbox-content` CSS classes so it looks consistent, but
      has no `.metadata-sidebar`, no prev/next nav, no edit/delete actions — just the
      full image/video plus a close button (click outside, `X`, or Escape). Deliberately
      **not** a route/navigation change — the user stays on exactly the page they were
      on; the popup is just an overlay.
      - Backend: `TaskDTO` (`model/TaskDTO.java`) only exposed `id`/`status`; added
        `photoId`. `TaskEntity.photoId` is generated up front at task creation
        (`UploadController.java`, `UUID.randomUUID()`) and reused as the final
        `PhotoEntity`'s id in `PhotoUploadService.processNewAsset`, so it's already
        correct and available even mid-poll, not just once `COMPLETED`. Updated
        `TaskService.getTask`'s mapping to include it (only call site).
      - Frontend: `pollTaskStatus` now stores `task.photoId` onto the matching queue item.
        `UploadPreviewModal` takes just a `photoId` + `onClose`, fetches
        `GET /api/photos/{photoId}` itself (same fallback-fetch pattern already used in
        `useAppNavigationAndSelection.js` for hard-refreshed asset URLs) to get
        `contentType`/`fileHash`/`title`, then renders `<img>`/`<video>` against
        `/original` or `/stream` with the usual `?v=fileHash` cache-busting.
      - First iteration of this feature scrolled the grid to the card and highlighted it
        in place instead of popping up a preview (`data-photo-id` on `PhotoGrid.jsx`'s
        card, `.just-uploaded-highlight` CSS pulse) — superseded by this popup approach
        per explicit user request; the scroll/highlight code and its
        `data-photo-id` attribute were removed again.
      - `components/UploadManager.test.jsx` covers: "Show" button appears once
        `COMPLETED` with a `photoId`; clicking it renders the popup's `<img>` with the
        correct cache-busted `src` and no `.metadata-sidebar` in the DOM; Escape closes
        it.
      - **Explicitly out of scope** (per user decision): no equivalent for Space uploads.
        Note for later: this would *not* actually need a new `/spaces/:spaceId/asset/:photoId`
        route (the app's `backgroundLocation` trick means the visible page is whatever
        `backgroundLocation.pathname` resolves to, regardless of the real URL) — the real gap
        is that `useAppNavigationAndSelection.js`'s `photoIdMatch` regex and `handlePhotoClick`'s
        URL-building only branch on `/asset/` and `/albums/.../asset/`, never `/spaces/.../asset/`,
        so opening a photo from a Space today always falls through to plain `/asset/:photoId`
        (Library context) — same gap already flagged elsewhere in this file. Doesn't matter for
        this popup (it never navigates/routes at all), but relevant if this gets picked up later.
      - **Follow-up**: the user independently reshaped the chunk-upload completion response
        on the backend (`UploadController.java`, `model/UploadChunkResponse.java`) from a flat
        `{status, taskId}` map to `{status, chunkIndex, task}` where `task` is a full `TaskDTO`
        (`id`/`status`/`photoId`) — consistent with what `GET /api/tasks/{id}` already returns.
        Updated `UploadManager.jsx` (frontend only, per explicit instruction not to touch the
        already-changed backend) to read `data.task.id`/`data.task.photoId` instead of the old
        `data.taskId`, and to seed `photoId` on the item immediately from that first response
        rather than waiting for the first poll tick (previously up to 3s later for no reason,
        since the backend generates `photoId` at task-creation time — see above). Updated the
        mocked chunk-upload response shape in `UploadManager.test.jsx` to match.
      - **UX refinement, per explicit user feedback**: three changes to the above.
        1. The preview link's eligibility rule is now explicit: available for every queue-item
           status **except `PENDING` and `FAILED`** (`canPreview = item.photoId && status !==
           'PENDING' && status !== 'FAILED'` in `UploadManager.jsx`), not just `COMPLETED`.
           Note found while live-testing this against the real backend (not just mocks): the
           item's status briefly goes `UPLOADING` → `PROCESSING` (frontend-optimistic, set the
           instant the last chunk lands) → **`PENDING`** (the *real* backend task status once
           polling starts, if the worker hasn't picked it up yet) → `COMPLETED`. So there's a
           genuine window where the link is clickable (`PROCESSING`) before the backend has
           actually created the `Photo` row yet, and `GET /api/photos/{id}` legitimately 404s if
           clicked right then — confirmed live. This is expected given "except PENDING and
           FAILED" was the explicit spec (PROCESSING is intentionally included); the popup's
           existing "Loading..." fallback (never turns into an error state) is the only concession
           to that race today — not hardened further since it wasn't asked for.
        2. No more separate "Show" button — the file name itself (`upload-item-name`) is now the
           click target when `canPreview` is true, rendered as a real `<button>` (for
           accessibility/keyboard support) with a `.upload-item-name-link` modifier class
           (button-chrome reset + `var(--primary-color)`, underline on hover) instead of a plain
           `<span>`. Removed the now-unused `viewUploadedAsset` i18n key (en/fr) and the old
           `.view-asset-link` CSS rule.
        3. `UploadPreviewModal.jsx` no longer reuses the full-screen `.lightbox-overlay` styling.
           New `.upload-preview-overlay` is a `position: fixed; inset: 0` **transparent**
           click-catcher (for click-outside-to-close only — no dark backdrop), and
           `.upload-preview-popup` is a small centered card (`max-width/height: min(90vw/vh,
           480px)`, dark card background, rounded corners, shadow) so the page behind — where the
           user is actively uploading — stays fully visible around it. Close button repositioned
           to sit just inside the card's corner (`top/right: 8px`) instead of the old far-corner
           lightbox positioning.
        - Updated `UploadManager.test.jsx` throughout: clicks the file-name button instead of a
          "Show" button; added a `describe('UploadManager preview link eligibility')` block
          covering the FAILED-item case (no link) and a PENDING→COMPLETED transition (no link
          while PENDING, appears once past it).
        - **Test-flakiness gotcha hit and fixed**: the two popup-behavior tests used to finish
          (via `findByRole`) as soon as `photoId` arrived — before their own `pollTaskStatus`
          `setInterval` ever ticked once (3s), since eligibility no longer waits for a poll at
          all. That left a live `setInterval` still pending when the test function returned, so
          Vitest moved on to the *next* test while it was still ticking in real time — and when it
          eventually fired, it called that *next* test's `global.fetch` mock (fetch is looked up
          on `globalThis` at call time, not closure-captured), corrupting a counter-based mock in
          the PENDING/COMPLETED eligibility test with an extra, unexpected call. Fixed by having
          both popup tests explicitly `await screen.findByText('COMPLETED', ...)` at the end so
          the interval actually clears itself (`isTerminal` → `clearInterval`) before the test
          exits. General lesson: any test whose component starts a `setInterval`/`setTimeout` that
          only self-clears on a *later* state needs to wait for that terminal state before
          finishing, even if the assertions it cares about resolve earlier — otherwise the timer
          outlives the test and can corrupt whatever runs next.
        - Verified live against the real backend (not just mocks) via a scratch Playwright
          script: uploaded a real JPEG, watched the file name link appear/disappear correctly as
          status moved `PROCESSING` → `PENDING` → `COMPLETED`, confirmed the popup has a
          transparent overlay (`getComputedStyle(...).backgroundColor` = `rgba(0,0,0,0)`) and a
          small (~144×144 for that test image) centered card, with the Library grid still fully
          visible behind it.
- [x] **Simplification + bug fix**: `POST /api/photos/selected` request contract
      (`hooks/useMenuSelection.js`, backend `MenuSelectionRequest`/`PhotoService.getSelection` —
      backend side done by the user directly). Two changes, requested together:
      1. Dropped the separate `lightbox: boolean` field. When the Lightbox is open, `menu` is now
         simply `"lightbox"` (overriding whatever the underlying page's menu would otherwise be —
         `"library"`/`"album"`/`"space"`/`"garbage"`/etc.), instead of sending both the page menu
         *and* a `lightbox` flag alongside it.
      2. **Real, pre-existing bug found and fixed while touching this same request**: the frontend
         sent JSON keys `spaceId`/`albumId` (singular), but the backend record's fields are
         `spaceIds`/`albumIds` (plural) — Jackson doesn't fail on an unrecognized/missing property
         by default, so those always deserialized to `null` server-side. Confirmed live against
         the real backend with a real album id: `canBeSetAsCover` was `false` under the old
         `albumId` key and `true` under `albumIds` for the identical request otherwise. This
         silently broke `canBeSetAsCover`, the album-menu branch of `canBeRemovedFromAlbum`, and
         the space-based `canBeMovedToAlbum`/`canBeMovedToMain` checks — i.e. real user-facing
         capability flags were wrong for as long as this mismatch existed. Fixed by renaming the
         request body keys in `useMenuSelection.js` to `spaceIds`/`albumIds`.
      - Added regression tests in `hooks/useMenuSelection.test.js`: `menu` becomes `"lightbox"`
        with no `lightbox` property in the payload when the Lightbox is open (overriding the
        underlying album/space/garbage menu); the request body uses `spaceIds`/`albumIds`, not
        `spaceId`/`albumId`.
      - Updated `specification.md` (§3 Header, §5.2 `POST /api/photos/selected`) to describe the
        new contract.
- [x] **Feature**: "Move to Main Space" action in the Lightbox sidebar
      (`components/Lightbox.jsx`), gated by `photoSelection.canBeMovedToMain` — same flag
      `Header.jsx` already uses for its own "Move to Main" button, so visibility is consistent
      between the grid bulk-action and the single-photo Lightbox action.
      - Added `handleMoveToMain(ids)` to `hooks/usePhotoActions.js`, mirroring
        `handleMoveToGarbage`'s shape (`POST /api/photos/main`, clears selection + refreshes on
        success, alerts on failure) but without a confirmation dialog — matches `handleRestore`,
        and every other single-photo Lightbox action button, none of which confirm.
      - Threaded `handleMoveToMain` through `App.jsx` → `LightboxWrapper.jsx` → `Lightbox.jsx`
        (new prop at each level, same pattern as `handleMoveToGarbage`/`handleRestore`).
      - Button used a `Home` icon, placed after "Move to Garbage" in the sidebar's action list
        (later restyled along with every other Lightbox action button — see next entry).
      - Added `hooks/usePhotoActions.test.js` coverage (success clears selection + refreshes;
        failure alerts instead of failing silently) mirroring the existing `handleRestore` tests.
- [x] **Visual refresh, Lightbox metadata sidebar** (`App.css`, `components/Lightbox.jsx`), per
      explicit user feedback that it "looks a bit old." Two changes:
      1. **Action buttons**: previously a mix of plain white `.btn` pills and solid saturated
         fills (`#34c759` green, `#ff3b30` red, `#28a745` green, `rgba(255,255,255,0.2)` grey) —
         every button looked like its own disconnected block, and "Move to Garbage" (soft/
         reversible) had no visual distinction from "Delete Permanently" (destructive/permanent).
         Replaced with one `.lightbox-action-btn` family in `App.css`: a neutral translucent-
         outline base (`rgba(255,255,255,0.06)` fill, subtle border) plus `.primary`/`.success`/
         `.danger` modifiers using tinted-outline versions of the app's existing semantic colors
         (`var(--primary-color)`, `#34c759`, `#ff3b30`) instead of solid fills — color now signals
         meaning (Edit Image = primary CTA; Restore/Move to Main = positive; Delete Permanently =
         destructive) rather than being applied inconsistently. Also reordered the action list by
         severity (positive actions → neutral Move to Garbage → destructive Delete Permanently)
         instead of the previous arbitrary order.
      2. **Metadata rows**: `.metadata-group label` (File Name / Date Taken / Basic Details /
         etc.) now matches `.meta-divider`'s existing uppercase-muted-label style (`11px`,
         `#86868b`, uppercase, letter-spacing) instead of a plain 13px grey label — makes the
         "field name" hierarchy consistent across the whole sidebar instead of only the
         "TECHNICAL" section header looking that way. Softened `.metadata-group`'s divider from
         `rgba(255,255,255,0.1)` to `rgba(255,255,255,0.06)` so rows read as loosely grouped
         rather than hard-ruled.
      - No JS/behavior changes — pure CSS + className swaps (removed inline `style={{...}}` button
        styling from `Lightbox.jsx` in favor of the new shared classes). Verified visually via a
        scratch Playwright screenshot before/after; 69/69 tests still pass (none of them assert on
        button styling/order), lint clean, build succeeds.

- [x] **Backend improvement**: `DuplicationService.java`'s duplicate detection was found to be
      "not very powerful" (global pHash/averageHash comparison only). Added a second tier:
      ORB keypoint feature matching + RANSAC homography (`confirmWithFeatureMatching`,
      `countHomographyInliers`), used to confirm duplicates the whole-image hashes alone can't
      distinguish from "just similar" (crops, rotations beyond EXIF correction, localized edits).
      Two-tier design agreed with the user, since running ORB on every pair in a space would be
      much more expensive than a 64-bit Hamming distance:
      1. Tight hash match (pHash ≤ 12 AND averageHash ≤ 10, unchanged) → confirmed directly, no
         ORB needed (cheap fast path for near-exact re-uploads/recompressions).
      2. Looser "plausible" band (pHash ≤ 24 OR averageHash ≤ 20) → falls back to ORB matching;
         only confirmed as a duplicate if enough good matches (≥15, Hamming distance ≤ 64) also
         agree on a single geometric transform (≥10 RANSAC inliers) — the homography check exists
         specifically to reject coincidental keypoint matches on repetitive textures (foliage,
         brick walls, etc.) that would otherwise false-positive a plain match-count threshold.
      - Confirmed via a live curl check that `fileHash`-based exact-duplicate blocking already
        happens at upload time (`PhotoUploadService.java`), so this service's job is specifically
        near-duplicates, not byte-identical files — no exact-hash tier was added here, it would be
        redundant.
      - Known limitation (not fixed, scope tradeoff): a very heavy crop/zoom that pushes both
        hashes outside the "plausible" band is still never compared, since ORB is gated behind the
        tier-1 hash band rather than run on every pair in the space. Documented in
        specification.md §4.3.
      - Verified with `javac` directly against the `~/.m2` classpath (no `mvn` available in this
        shell) — compiles cleanly. **Not run/tested against real photos yet** — the user should
        build and try it against their own library before trusting the new thresholds.
- [x] **Backend bug fix**: `DuplicationUniquenessService.consolidateDuplicates` — the "join an
      existing duplicate group" condition was `group ⊆ ids && group.size() - 1 >= ids.size()`.
      The subset check already implies `group.size() <= ids.size()`, which directly contradicts
      `group.size() >= ids.size() + 1` — the two can never both be true (outside a narrow
      self-referential edge case), so **the join branch was dead code**: every detected duplicate
      always created a brand-new, overlapping `DuplicateEntity` group instead of extending one
      that already existed. Rewrote the method to treat duplicate clusters as connected
      components: it now merges *every* existing group touched by the newly-detected duplicates
      (not just the first candidate) into one group — keeps one group's id, recomputes `hash` from
      the full merged membership (the old code never recomputed `hash` after mutating membership
      either, even on the rare path where joining did fire), deletes the other touched groups, and
      reopens the kept group to `OPEN` status if it had previously been `CLOSED` (a new duplicate
      means there's something new to review). Found by careful reading of the boolean logic (not
      live-tested against real duplicate data — no existing duplicate groups in the dev DB to
      exercise the merge path against). Verified with `javac` against the `~/.m2` classpath;
      compiles cleanly. Documented in specification.md §4.3.

- [x] **Feature**: inline duplicate grouping in `PhotoGrid.jsx` (Library/Album/Space/Garbage grid),
      consuming the backend's per-photo `duplicates: Photo[]` field.
      - Clusters each photo with its `duplicates` into one display item (`consumedIds` set prevents
        a duplicate photo from also rendering as its own separate top-level card later in the same
        list). Renders collapsed by default (one card + a `.duplicate-badge` count pill, click to
        expand) or expanded (`.duplicate-cluster` wrapper showing every member as its own full card
        with the existing checkbox overlay, plus a collapse button).
      - Deliberately reused the app's existing select-then-Header-bulk-action pattern for moving an
        expanded duplicate to the garbage, instead of adding a new per-card delete button/confirm
        dialog — matches how deletion already works everywhere else in the grid (and in
        `DuplicatePhotoGrid.jsx`'s own comparison view). Worth flagging if the user actually wanted
        a dedicated one-click trash icon per duplicate instead.
      - **Backend gap found while implementing** (not fixed, out of frontend scope): only
        `PhotoService.getPhotos`/`getSpacePhotos` (library + space) populate `duplicates` on the
        `Photo` DTO; `getGarbagePhotos` and `AlbumService.getAlbumPhotos` don't call
        `duplicationService.getDuplicates(entity)` yet, so grouping silently won't show there until
        that's added — frontend handles this gracefully (`p.duplicates || []`), no crash, just no
        grouping UI on those two views yet.
      - Added `PhotoGrid.test.jsx` coverage: collapsed cluster renders as one card with a badge (not
        N separate cards), expand shows every member as its own selectable card, collapse folds back,
        and an individual expanded duplicate can be selected via its checkbox (feeding the existing
        bulk Move-to-Garbage flow). 72/72 tests pass, lint clean, build succeeds. Not verified live
        in a browser — no existing duplicate-group data in the dev DB to exercise it against; the
        user should try it against real duplicate data once available.

- [x] **Bug fix**: reported symptom — opening the Lightbox for a photo on the 2nd (or last)
      page, then closing it, briefly flashed the Login page before landing back on the
      library, and the grid only showed the first couple of pages afterward (needed a manual
      scroll up/down to resume loading). Two separate root causes, both fixed:
      1. `hooks/usePhotoData.js`'s load-on-path-change effect had `isLightboxOpen` in its
         dependency array with no guard against it merely toggling closed on the *same*
         `effectivePath` — so every Lightbox close re-ran `loadPhotos(0, reset=true)`,
         silently discarding any pages loaded past page 0. Fixed with a `loadedPathRef` that
         only allows the reset when `effectivePath` actually changed (or this is the first
         load for it, e.g. landing directly on an `/asset/:id` URL — the case the
         `isLightboxOpen` guard originally existed for, still preserved).
      2. `hooks/useAuth.js`'s `checkAuthStatus` (the `/api/user/me` fetch) was keyed on
         `location.pathname` in its `useCallback` deps, so the *whole session check re-ran on
         every client-side navigation* — including the navigate-back that happens when closing
         the Lightbox. Any transient hiccup on that extra, unnecessary fetch could flip
         `isAuthenticated` to false and bounce to `/login`, then bounce right back once a
         subsequent check succeeded — exactly the observed flash. Split into two effects: the
         actual `/api/user/me` fetch now runs once on mount only; a separate, network-free
         effect reacts to `location.pathname`/`isAuthenticated`/`loadingAuth` purely to decide
         redirects, using already-known state instead of re-verifying the session.
      - Added regression coverage in `usePhotoData.test.js` (Lightbox close on the same view
        doesn't re-fetch/drop loaded pages; closing after landing directly on an asset URL
        still triggers the deferred first load). `useAuth.test.js`'s existing suite still
        passes unchanged (asserts the same redirect outcomes, now decided without a second
        fetch). 74/74 tests pass, lint clean, build succeeds. Not verified live against the
        exact reported scenario (2nd/last page + close) — the logic is a direct match for the
        symptom, but worth confirming against the real backend.

- [x] **Regression fix (introduced by the previous entry, same session)**: the `useAuth.js`
      "run checkAuthStatus once on mount only" fix broke logging in at all. `Login.jsx` had zero
      connection to `useAuth`'s state — it just did `navigate('/')` after a successful
      `POST /api/login` and relied entirely on the *old* behavior of `checkAuthStatus` re-running
      on every `location.pathname` change to notice the freshly-established session. Once that
      re-run-on-navigate behavior was removed, `isAuthenticated` never flipped to `true` after a
      correct login, and the new redirect effect (correctly, given stale state) immediately bounced
      back to `/login` — i.e. "can't log in anymore" even with the right credentials.
      - Fixed by returning `checkAuthStatus` from `useAuth.js` and threading it down to both
        `<Login>` routes and `<ShareLoginPage>` (`App.jsx`); each now calls
        `await checkAuthStatus()` right after its login POST succeeds, before navigating —
        re-verifying the session on demand instead of relying on incidental re-checks.
      - Also fixed `ShareLoginPage.jsx` for the identical root cause: it navigates to
        `/albums/:albumId`, which is only reachable inside `App.jsx`'s `isAuthenticated` branch,
        and depended on the same removed side-effect.
      - Added `components/Login.test.jsx` (new file) to lock this in: success calls
        `checkAuthStatus` then navigates to `/`; failure does neither. 76/76 tests pass, lint
        clean, build succeeds. **Could not verify live against the user's real account** (no
        credentials available in this session) — the user should confirm login works again.

- [x] **Follow-up on duplicate grouping**, per direct user feedback on the initial version:
      1. "Can't see the top of the window" for the expanded cluster: the collapse button was an
         absolutely-positioned `top:-10px` overlay poking above the cluster's own box — plausible
         culprit is it getting visually clipped under `.day-header`'s `position: sticky` depending
         on scroll position (first row of a day-group especially). Restructured the expanded
         cluster to a real in-flow header row (`.duplicate-cluster-header`) above the card row
         instead of an overlaid badge, so there's nothing that can end up hidden behind the sticky
         header regardless of scroll position.
      2. "Make the window larger": expanded-cluster member cards now render at 360px
         (`EXPANDED_CLUSTER_CARD_SIZE` in `PhotoGrid.jsx`) instead of the grid's normal 220px, via
         a new `size` param on the shared `renderPhotoCard` helper — normal single cards and the
         collapsed cluster's primary card are unaffected (still 220px).
      3. Added a per-card garbage icon (`.duplicate-remove-btn`, `Trash2`) on each member,
         **visible only while the group is expanded** — calls
         `handleMoveToGarbage([memberId])` directly, one click, no need to select+use the Header
         bulk action first. This explicitly supersedes the initial version's design choice (select
         checkbox + existing Header bulk action only, no per-card button) — the user asked for the
         dedicated icon after all. Both mechanisms coexist now (checkbox still selects for bulk
         actions too). Required threading `handleMoveToGarbage` as a new prop through
         `PhotoGrid`/`AlbumDetail`/`SpaceDetail` from `App.jsx` (it already existed via
         `usePhotoActions`, just wasn't passed down to the grid before).
      - Extended `PhotoGrid.test.jsx`: remove icon absent while collapsed, present once expanded
        (one per member), clicking it calls `handleMoveToGarbage` with just that member's id. 77/77
        tests pass, lint clean, build succeeds. Not verified live in a browser (no duplicate-group
        data in the dev DB) — the sticky-header-clipping diagnosis for issue #1 is a plausible
        explanation fitting the description, not something directly observed/reproduced here.
- [x] **Diagnostic logging**, per request to investigate too many false-positive duplicates:
      `DuplicationService.evaluatePair` now logs pHash/mHash distances against both the strong and
      candidate thresholds for every pair, plus an explicit verdict (strong-hash match / outside
      candidate band / feature-match confirmed / feature-match rejected).
      `confirmWithFeatureMatching` logs keypoint counts, good-match count vs. `ORB_MIN_GOOD_MATCHES`,
      and homography inlier count vs. `ORB_MIN_INLIERS`. `checkDuplication` logs the date window
      scanned per photo, candidate page sizes, and the final duplicate set. Not yet run against
      real data — next step once logs come back is to see which gate (hash thresholds vs. ORB/
      homography thresholds) is actually letting false positives through, then tighten the
      relevant constant.
- [x] **Bug fix**: reported symptom — a failed upload (`Uploads` panel showing `FAILED` with no
      error text and no Retry button, e.g. `IMG_20260504_162457.jpg`). Root cause: the Retry
      button lived inside `{item.error && (...)}` in `UploadManager.jsx`, but
      `TaskWorkerManager.processTask`'s catch-all did `task.setErrorMessage(e.getMessage())` — for
      exceptions with no message (confirmed: `NoUserFoundException`, `NoMatchUserSpaceException`
      both have only a no-arg constructor, so `getMessage()` is always null), `errorMessage` came
      back `null` from `/api/tasks/{id}`, so the whole error block — text *and* Retry button —
      silently never rendered even though the item was genuinely `FAILED`. Fixed both sides:
      backend now falls back to `e.getClass().getSimpleName()` when `getMessage()` is null, and
      frontend now renders the error block (with a new `unknownUploadError` fallback string) and
      the Retry button whenever `item.status === 'FAILED'`, independent of whether `item.error` is
      set. Added a regression test in `UploadManager.test.jsx` covering a task that fails via
      polling with `errorMessage: null`. 78/78 tests pass (including the two pre-existing Retry
      tests for client-rejected files and server chunk-upload failure, which already covered the
      cases where `error` was non-null).
- [x] **Backend perf/correctness refactor**: ORB descriptors/keypoints are now computed once at
      upload time and persisted, instead of being recomputed (reload both images from disk + rerun
      ORB detection) on every candidate comparison. `PhotoEntity` gained `orbDescriptors` (raw ORB
      descriptor matrix bytes, row-major, `ORB_DESCRIPTOR_COLS`=32 bytes/keypoint) and
      `orbKeypoints` (keypoint x/y as 4-byte floats, index-aligned with the descriptor rows) —
      `BYTEA` columns picked up automatically via `ddl-auto: update`, no manual migration needed.
      `DuplicationService.preprocess` now also calls the new `computeAndStoreOrbFeatures` on the
      same rotated+resized image already used for pHash/averageHash (grayscale via `cvtColor`).
      `confirmWithFeatureMatching` was rewritten to reconstruct a `Mat`/point array from the two
      photos' stored bytes (`bytesToDescriptorMat`, `bytesToPoints`) instead of loading images —
      `loadGrayscaleForFeatureMatching` and the old `KeyPointVector`-based
      `countHomographyInliers` overload were removed as dead code. Side effect worth noting: the
      old per-comparison path used `imread(path, IMREAD_GRAYSCALE)` directly and never called
      `autoRotate`, so EXIF-rotated photos were compared unrotated for the feature-matching tier
      specifically (pHash/averageHash were unaffected, they always went through `preprocess`) —
      that's now fixed as a byproduct, since the stored descriptors come from the same
      rotate-then-resize pipeline as the hashes. Photos hashed before this change have no stored
      descriptors yet; `confirmWithFeatureMatching` logs and returns `false` for those (candidate
      pairs just fall through to "not confirmed") until they're re-hashed — not currently
      backfilled, no migration/re-hash job was requested. Verified via direct `javac` compilation
      against the `~/.m2` classpath (no `mvn` available in this shell) — not run against real
      photos.
- [x] **Bug fix**: real false-positive duplicate confirmed via the new diagnostic logging — two
      completely unrelated photos (kids playing ping-pong outdoors vs. two people at an indoor
      birthday party) were flagged as duplicates by the ORB tier. Logs showed exactly why: 90
      "good" Hamming-distance matches, but only 11 of them (12%) agreed on a single RANSAC
      homography — that cleared the old `ORB_MIN_INLIERS=10` absolute-count check, but a coherent
      match should have most of its good matches agree on the same transform, not 12% of them.
      RANSAC's job is to find the *largest* consensus set even from unrelated correspondences, so
      an absolute inlier count alone can't distinguish a genuine geometric match from a
      coincidental one found on repetitive texture (grass, wood grain, clothing folds — exactly
      what both photos had plenty of). Added `ORB_MIN_INLIER_RATIO = 0.5` in
      `DuplicationService.java`: `confirmWithFeatureMatching` now also requires
      `inliers / goodMatches.size() >= 0.5`, on top of the existing absolute-count check. Against
      this exact case (11/90 = 0.122), the pair now correctly fails and is not flagged. Verified
      via `javac` compilation; not re-run against the reported photos live (no way to trigger a
      re-check from this shell) — worth confirming this specific pair no longer groups after the
      next duplicate scan.
- [x] **Bug fix + improvement**: tiny-face handling in `FaceDetectionService`/`FaceAligner`, per
      report that detection works well except for small faces, and that including them anyway had
      previously been tried and reverted for producing bad results.
      1. **Real bug found in `FaceAligner.align`**: `leftEye`/`rightEye` were each read via a
         freshly-created `points.iterator()`, so both got the same first landmark point —
         `rightX-leftX`/`rightY-leftY` were always `0`, making the eye-line rotation a permanent
         no-op regardless of actual head tilt. Every face has been aligned unrotated this whole
         time, not just small ones — but uncorrected tilt costs a much bigger fraction of the
         available signal on a small crop than a large one, which lines up with why small faces
         specifically looked worse. Fixed by reading both points off one shared iterator instance.
      2. Bumped both `AffineTransformOp`/`Graphics2D` interpolation from `BILINEAR` to `BICUBIC` —
         sharper when upscaling a small crop to the embedding model's fixed 112×112 input.
      3. Replaced the flat `w < 60 || h < 60` exclusion in `FaceDetectionService` with
         `MIN_FACE_SIZE = 32` (hard floor) plus a confidence-aware band for faces between 32px and
         the old 60px threshold: kept only when detector confidence ≥ `SMALL_FACE_MIN_CONFIDENCE =
         0.92` (stricter than the model's own `confThresh = 0.85` in `FaceDetectionConfiguration`).
         RetinaFace's smallest anchor tier (scale=16) is built to find faces well under the old
         60px floor, so the flat cutoff was discarding faces the detector correctly found; the
         confidence gate exists to control the added false-positive risk now that they're no
         longer blocked outright — was likely a real contributor to "including tiny faces didn't
         work well" before, on top of the alignment bug.
      4. Added logging for kept/skipped small-face decisions (size, confidence, which gate fired)
         to make future tuning of `MIN_FACE_SIZE`/`SMALL_FACE_MIN_CONFIDENCE` observable.
      Verified via `javac` compilation against the `~/.m2` classpath; not run against real photos
      (no `mvn`/live app access from this shell) — worth a real check on whether small/background
      faces are now detected and recognized well, and whether `SMALL_FACE_MIN_CONFIDENCE` needs
      tuning from there.
- [x] **Bug fix**: face bounding-box overlays in the Lightbox (`Lightbox.jsx`) drawn in the wrong
      position relative to the actual faces — confirmed via screenshots showing boxes offset from
      the real face locations by a different amount/direction per photo, while the backend-cropped
      face thumbnails in the sidebar were correctly framed (so the bug was frontend-only, in how
      the boxes are drawn over the full image, not in detection itself). Two compounding causes:
      1. `.face-box` (`position: absolute`) never had a correctly-positioned ancestor: its
         immediate parent `zoom-container` had no `position` set at all, so it fell through to
         `.lightbox-content` (`App.css`) — which has `padding: 40px` — as the actual positioning
         context. Fixed by adding `position: relative` to `zoom-container`'s inline style.
      2. Even with that fixed, `handleImageLoad` computed `scaleX`/`scaleY` from the `<img>`
         element's own `clientWidth`/`clientHeight`, with no offset for the fact that
         `object-fit: contain` centers the image inside `zoom-container` — whenever the photo's
         aspect ratio doesn't exactly match the container's (i.e. almost always), the image is
         inset from the container's edges by a letterbox gap that was never accounted for. Notably,
         `clampPan` a few lines above already computes this exact "rendered size within container"
         math for pan-clamping, with a comment saying "This accounts for object-fit: contain" — but
         that fix was never applied to the face-box code path. Fixed by reusing the same rendered-
         size computation in `handleImageLoad` and adding the resulting `offsetX`/`offsetY` (half
         the letterbox gap on each axis) into the face-box `left`/`top`.
      Verified with the existing frontend suite (78/78 pass, unrelated to this file) and lint
      (0 new warnings/errors on `Lightbox.jsx`) — not visually confirmed in a live browser against
      the reported photos from this shell; worth a quick look in the app to confirm the boxes now
      land on the actual faces for both reported cases.
- [x] **Redesign**: `FaceRecognitionService`'s face-to-person clustering, per request to reliably
      group the same person's face across large age gaps (e.g. age 5 vs. age 25) without loosening
      match confidence. Discussed and confirmed with the user first: found and flagged a real
      cross-user scoping bug (`MainSpaceAspectConfiguration.enableSpaceFilter` skips enabling the
      `spaceFilter` for any thread named `backend-task*`, and `FACE_RECOGNITION` always runs on
      such a thread via `TaskWorkerManager` — so `@MainSpaceLimited` queries in this path are
      currently unfiltered) — user explicitly said not to touch `MainSpaceAspectConfiguration` for
      this change, so it's left as-is/out of scope, not fixed.
      1. **Matching against existing persons** (`findBestMatchForFace`/`scoreAgainstPerson`): now
         compares a new face against each candidate person's `TEMPORAL_NEIGHBOR_WINDOW` (=10)
         faces closest in time — both before AND after (new `findByPersonAndPhotoDateTakenAfter
         OrderByPhotoDateTakenAsc` in `FaceRepository`, since only "before" existed previously) —
         instead of averaging across the person's whole history (which dilutes the score across
         eras) or a two-tier retry with arbitrary page sizes (20, then 100). Confirmed with the
         user: windowing is per-person-candidate, not a single global pass.
      2. **Clustering leftover ungrouped faces into new people** (`clusterByTemporalProximity`,
         replacing `clusterFaces`): the old version anchored each new cluster on the first
         unclustered face and compared everyone else directly to that one anchor (single-link from
         one fixed point, no chaining) — direct 5-vs-25 comparisons were never going to pass a
         strict threshold. New version sorts the batch by photo date, compares each face only to
         its next `TEMPORAL_NEIGHBOR_WINDOW` faces chronologically (O(n*window), not O(n^2) — since
         sorted, this single forward pass already covers both directions for every pair), and
         merges matches via union-find (connected components) rather than single-anchor grouping —
         so two faces decades apart can land in the same cluster by chaining through the many
         in-between-age faces of the same person, without ever weakening the per-pair
         `THRESHOLD` (kept at 0.60, unchanged). This was an explicit design point raised and
         confirmed with the user: performance was flagged as a concern before implementing, hence
         the windowed/bounded approach instead of full pairwise comparison.
      3. Removed the old 100-face-cap batch for new-cluster formation, which could split one real
         person's faces into multiple `PersonEntity` rows if they landed in different pagination
         batches — now processes the full ungrouped set per run (capped at
         `MAX_UNGROUPED_FACES_PER_RUN`=2000 to bound memory; any backlog beyond that is picked up
         by the next run, since `FACE_RECOGNITION` reruns every ~30s per `TaskWorkerManager`).
      4. Removed `PersonRecognitionService.THRESHOLD` — a dead/unused constant (0.8, never
         referenced anywhere in that class).
      Verified via `javac` compilation against the `~/.m2` classpath; not run against real face
      data (no `mvn`/live app access from this shell, and no backend test suite exists yet — see
      the still-pending duplication/face-detection test conversation). Worth validating on a real
      account with an age-spanning photo history once deployed, and revisiting
      `TEMPORAL_NEIGHBOR_WINDOW`/`MIN_NEW_PERSON_CLUSTER_SIZE` from there if grouping is still too
      conservative or too loose.
- [x] **Bug fixes**: Persons page (`Persons.jsx`) cover-photo icon, per request to finish this
      feature ("an icon for each person with its latest known photo"). The intended behavior
      already existed (`PersonRecognitionService.recalculateCoverFace` picks the face whose photo
      has the most recent `dateTaken`) but was broken in two places:
      1. **`FaceService.mapToPerson` could crash the entire `/api/faces/persons` response**:
         `mapToDTO(personEntity.getCoverFace())` NPEs whenever any person's `coverFace` is `null`
         — and since this runs inside `Page.map()` over every person, one person with no cover
         face failed the whole list, not just that person's card. Fixed with a null check;
         `coverPhoto` is now `null` in the DTO rather than crashing.
      2. **`coverFace` could go `null` and never recover**: `FaceDetectionService.deleteFaces`
         nulls out a person's `coverFace` when its underlying photo is deleted (correct — the face
         no longer exists), but never picked a replacement, so the icon stayed blank indefinitely
         unless that person happened to get a brand-new matched face later. Fixed by having
         `deleteFaces` call `PersonRecognitionService.recalculateCoverFace` (made package-private
         for this) right after nulling the pointer — also removes the deleted face from the
         in-memory `person.getFaces()` collection first, since the face row hasn't actually been
         deleted from the DB at that point yet and `recalculateCoverFace` would otherwise just
         re-pick the same about-to-be-deleted face. Also moved the misplaced
         `faceRepository.deleteByPhotoId(photo)` call out of the per-face loop it was redundantly
         running inside (harmless but wasteful — deleted the same rows once per face).
      3. **Frontend**: `Persons.jsx` rendered `<img src={person.coverPhoto?.image || ''}>` for a
         person with no cover photo, which is a broken-image icon in the browser rather than a
         clean placeholder. Now renders a circular gray placeholder with a `User` icon
         (lucide-react) instead, styled to match the existing `.person-item img` circle
         (`.person-item-placeholder` in `App.css`), so a person always has *something* sensible
         shown even before/without a cover face.
      Verified backend via `javac` compilation; frontend via the existing test suite (78/78 pass)
      and lint (clean on `Persons.jsx`). Not visually confirmed in a live browser.
- [x] **Bug fix**: the date-edit controls in the Lightbox metadata sidebar (`Lightbox.jsx`) used
      the same `XCircle` icon for both the save/apply button and the cancel button — visually
      identical, no way to tell them apart at a glance. Save now uses `CheckCircle`; cancel keeps
      `XCircle`. Also added `title` tooltips to both (`messages["save"]`/`messages["cancel"]`,
      already existing keys reused from elsewhere in the same file). Verified with the existing
      test suite (78/78 pass) and lint (clean, same 2 pre-existing unrelated warnings on this file).
- [x] **Bug fix**: `activateFaceRecognition`/`updateImageTaskToDo` (Admin page,
      `UserManagement.jsx`) never reached the backend in dev — reported as the request landing on
      `localhost:5173/admin/...` (the Vite dev server itself) instead of the backend. Root cause:
      `AdminController` was the only controller in the app mapped at plain `/admin` instead of the
      `/api/...` prefix every other endpoint uses, and Vite's dev proxy (`vite.config.js`) only
      forwards `/api/*` to `localhost:8080` — so `/admin/**` requests were never proxied, just
      served (or 404'd) by Vite directly. Only breaks in `npm run dev`; production works by
      accident since Spring Boot serves the built frontend from the same origin. Fixed by aligning
      with the rest of the app's convention instead of special-casing the proxy: `AdminController`
      now maps at `/api/admin`, and both `fetch` calls in `UserManagement.jsx` updated to match.
      **Also required a security-config fix to avoid a privilege-escalation regression**:
      `SecurityConfig.java` had `.requestMatchers("/admin/**").hasRole("ADMIN")` — moving the
      controller without updating this matcher would have left `/api/admin/**` covered only by the
      generic `.anyRequest().authenticated()` fallback, letting any authenticated (non-admin) user
      call these endpoints. Updated the matcher to `/api/admin/**` in the same change. `/admin/users`
      (the React Router client-side route in `App.jsx`/`Sidebar.jsx`) is unrelated and untouched —
      it's a frontend-only path, not a backend call.
      Verified backend via `javac` compilation; frontend via the existing test suite (78/78 pass)
      and lint (clean, 1 pre-existing unrelated warning). Not verified live against a running
      `npm run dev` + backend pair from this shell.
- [x] **Bug fix**: `org.hibernate.TransientObjectException: ... references an unsaved transient
      instance of PersonEntity` at commit time when creating a brand-new person from a cluster of
      previously-ungrouped faces (`PersonRecognitionService.addPerson`, reached from
      `FaceRecognitionService.groupFacesForUser`). This is pre-existing logic I didn't touch during
      the clustering redesign — most likely never actually exercised before, since it needs a
      cluster of 3+ genuinely-new faces to reach `addPerson` at all, and very plausibly the
      `activateFaceRecognition` admin-endpoint fix from the same session is what made this path
      reachable for the first time. Root cause: `faces` passed into `addPerson` are detached
      entities (loaded outside this transaction), so `faceRepository.saveAll(faces)` triggers a
      Hibernate `merge()` for each — which needs the new `person` they're being pointed at to
      already be a genuinely-persisted row, not just a pending/not-yet-flushed insert. The old code
      called `personRepository.save(person)` (`persist`, deferred insert) and only wired up
      `faceEntity.setPerson(person)` before that, so by the time `saveAll(faces)` ran, `person`'s
      insert hadn't necessarily hit the DB yet. Fixed by calling `personRepository.saveAndFlush
      (person)` — forcing the insert immediately — before any face is associated to it. Verified
      via `javac` compilation; not verified against a live DB (no `mvn`/live app access, and no
      backend test suite exists yet).
- [x] **Feature**: new Person detail page (`/persons/:personId`, `PersonDetail.jsx`) — clicking a
      person's photo/icon on `/persons` now navigates here, showing all their photos with the
      same `PhotoGrid` UI as Library/Album/Space (selection, duplicate grouping, garbage icon).
      Deliberately mirrors `AlbumDetail.jsx`/`SpaceDetail.jsx` structure exactly (same header shape,
      same `useEntityById` fetch pattern).
      Backend additions: `GET /api/faces/person/{id}` (`FaceController`/`FaceService.getPerson`) —
      previously missing entirely; `useEntityById` had nothing to call for a single person before
      this. `PersonDTO.photoCount` was always `null` — now a real count via
      `photoService.findByPerson(person, Pageable.ofSize(1)).getTotalElements()`. Also fixed in
      passing: `FaceService.getPersonPhotos` threw a plain `RuntimeException` for a missing person,
      which the controller's `catch (NoSuchElementException e)` could never actually catch (500
      instead of 404) — changed to throw `NoSuchElementException`, matching every other "get by id"
      endpoint in the app.
      Frontend wiring: `usePhotoData`'s path-based routing extended with a `/persons/:id` → `type:
      'person'` case (mirroring `/albums/:id`/`/spaces/:id`); `useRouteContext` gained
      `currentPersonId`/`selectedMenu: 'person'`; `Persons.jsx`'s photo/icon click now navigates to
      `/persons/${id}` (the name label is still reserved for the existing rename-in-place flow).
      Opening a photo from the person page uses the plain `/asset/:photoId` URL, same as Space —
      no per-person asset sub-route was added (Album's `/albums/:id/asset/:photoId` is the outlier
      here, not the norm, and nothing needs a person-scoped deep link today).
      Verified backend via `javac` compilation; frontend via the existing test suite (78/78 pass),
      lint (no new warnings beyond the pre-existing `actualRefreshAndMaintainScroll` exhaustive-deps
      one, now also listing `currentPersonId` alongside the already-missing `currentAlbumId`/
      `currentSpaceId`/`selectedMenu`), and a production build (`npm run build` succeeds). Not
      visually confirmed in a live browser.
- [x] **Feature**: manually remove a face from a photo, in the Lightbox's "Faces Detected"
      section. Each face thumbnail now has a small trash-icon overlay button
      (`.face-remove-btn`) that deletes just that one `FaceEntity` — the photo itself is
      untouched, only that one face annotation goes away (e.g. for a false-positive detection).
      Backend: new `DELETE /api/faces/{id}` (`FaceController.deleteFace` /
      `FaceService.deleteFace`) — if the removed face was someone's `coverFace`,
      `recalculateCoverFace` picks a replacement immediately, same pattern as
      `FaceDetectionService.deleteFaces` (remove from the person's in-memory `faces` collection
      first, since the row isn't deleted from the DB yet at that point, or it would just get
      re-picked). `FaceService` gained a `PersonRecognitionService` dependency for this — no
      circular-dependency risk, `PersonRecognitionService` doesn't depend on `FaceService`.
      `FaceDTO` gained an `id` field (previously had none at all — `x/y/width/height/image` only —
      so the frontend had no way to address a specific face for deletion); the single place that
      constructs it (`FaceService.mapToDTO`) updated to pass `faceEntity.getId()`.
      Frontend: `Lightbox.jsx`'s `handleRemoveFace` calls the new endpoint then filters the face out
      of local `faces` state — this drives both the thumbnail list AND the on-image `.face-box`
      overlays from one source, so both update together with no extra state to manage.
      Verified backend via `javac` compilation; frontend via the existing test suite (78/78 pass),
      lint (same 2 pre-existing unrelated warnings on this file, no new ones), and a production
      build. Not visually confirmed in a live browser.
- [x] **Feature**: show the person's name under a face thumbnail in the Lightbox's "Faces
      Detected" section, instead of the generic "Face 1"/"Face 2"/... label, whenever that face
      is linked to a named `PersonEntity`. `FaceDTO` gained a `personName` field
      (`FaceService.mapToDTO`: `faceEntity.getPerson() != null ? faceEntity.getPerson().getName()
      : null` — lazy-loaded `@ManyToOne`, safe here the same way the pre-existing
      `faceEntity.getPerson()`/`getCoverFace()` lazy accesses already were, since these endpoints
      are plain HTTP-request-scoped calls under Spring Boot's default `open-in-view=true`, not the
      `backend-task` background-thread path). Frontend: `face.personName || messages["faceLabel"]
      (index)]` for both the thumbnail's `alt` text and its `<label>` — falls back to the existing
      "Face N" label for an unnamed or unrecognized face, so nothing changes for those. Verified
      via `javac` compilation, the existing test suite (78/78 pass), lint (no new warnings), and a
      production build. Not visually confirmed in a live browser.
- [x] **Feature**: person navigation, per explicit request — keep the persons list visible above a
      selected person's photos, shown as a grid when no person is selected (unchanged `/persons`
      behavior) but as a horizontal, arrow-scrollable strip when one is selected, so switching
      between people doesn't require going back to the grid.
      - New `hooks/usePersons.js`: extracted the `/api/faces/persons` fetch (+ loading/error state)
        out of `Persons.jsx` into a shared hook, since it's now used in two places.
      - New `PersonStrip.jsx`: horizontal row of every person's avatar + name, left/right
        `ChevronLeft`/`ChevronRight` arrow buttons scrolling the track via `scrollBy({left, behavior:
        'smooth'})`, the currently-viewed person highlighted (`.active`, primary-color ring +
        bold name) via a `selectedPersonId` prop compared against each person's id. Clicking any
        person in the strip navigates to `/persons/${id}`.
      - `PersonDetail.jsx` now renders `<PersonStrip selectedPersonId={personId} />` above
        everything else, including the loading/error/not-found states — so the strip (and the
        ability to jump to a different, valid person) stays usable even if the currently-linked
        person id is bad or 404s.
      - `Persons.jsx` refactored to consume `usePersons()` instead of its own local fetch effect —
        behavior unchanged (still a grid, still supports the existing rename-in-place flow via the
        hook's `setPersons` for the optimistic update).
      - New i18n keys: `scrollPersonsLeft`/`scrollPersonsRight` (arrow button `aria-label`s).
      Verified with the existing test suite (78/78 pass — no regressions from the `Persons.jsx`
      refactor), lint (0 warnings/errors on every touched/new file), and a production build. Not
      visually confirmed in a live browser — worth checking the strip's scroll behavior and the
      active-person highlight render correctly, and that clicking through several people in a row
      feels smooth.
- [x] **Feature**: unlink a person from one specific photo, per explicit request — an icon on the
      top-right of every photo card on a person's page (`/persons/:personId`), only there (not on
      Library/Album/Space grids). Unlinks just that one person↔photo association — does NOT delete
      the underlying `FaceEntity` (that's the separate, pre-existing "remove face" action in the
      Lightbox) and does NOT delete the photo. The photo simply stops showing up for this person on
      their next refresh, since it's no longer matched to them.
      - Backend: new `DELETE /api/faces/person/{personId}/photo/{photoId}`
        (`FaceController.unlinkPersonFromPhoto` → `FaceService.unlinkPersonFromPhoto`) — finds the
        face(s) linking that person to that photo (new `FaceRepository.findByPersonIdAndPhotoId`),
        sets `person = null` on each. If one of them was the person's `coverFace`, picks a
        replacement immediately (`recalculateCoverFace`) — same established pattern as
        `FaceService.deleteFace`/`FaceDetectionService.deleteFaces`, including removing the
        unlinked face(s) from the person's in-memory `faces` collection first so the recalculation
        doesn't just re-pick one of them. Compares faces by `.getId()`, not `.equals()` — noted
        while writing this that `FaceEntity`'s `@EqualsAndHashCode(onlyExplicitlyIncluded = true)`
        has no field actually marked `@EqualsAndHashCode.Include`, making `.equals()` unreliable
        for identity comparison; worth a look separately, not fixed here since it's out of scope.
      - Frontend: `PhotoGrid.jsx` gained an optional `handleUnlinkPerson` prop (only ever passed
        from `PersonDetail.jsx`, same "only renders when the prop is provided" convention as the
        existing duplicate-cluster remove button) — renders an `Unlink2` icon button
        (`.unlink-person-btn`, top-right, mirrors `.duplicate-remove-btn`'s circular style) on every
        card when present. `PersonDetail.jsx`'s new `handleUnlinkPerson` calls the endpoint then
        `refreshAndMaintainScroll()` (now threaded into `PersonDetail` as a prop from `App.jsx`) —
        same call-then-refresh pattern as every handler in `usePhotoActions.js`.
      Verified via `javac` compilation (repository/service/controller together), the existing
      frontend test suite (78/78 pass), lint (no new warnings), and a production build. Not
      verified against the live app from this shell (backend running via IntelliJ, frontend build
      currently blocked by the separate pom.xml phase-ordering issue reported earlier this
      session) — worth a real click-through once that's resolved.
- [x] **Feature + bug fixes**: completed and fixed the share-link feature (it was already
      partially implemented, but several core paths were actually broken end-to-end, not just
      unverified — see specification.md §4.4 for the full current design).
      - **Address scheme changed**: `/share/{name}` → `/share/{userMd5}/{name}`, where `userMd5`
        is `md5(user.id)` (`ShareService.computeUserMd5`), computed once and stored on the new
        `ShareEntity.userMd5` column. Disambiguates two different users each naming a share
        `vacation-2024` without exposing either user's real UUID in the URL. Opening a specific
        photo: `/share/{userMd5}/{name}/p/{photoId}`.
      - **Real bugs found and fixed** (these 500'd or silently failed before, not just "unverified"
        as previously flagged here):
        - `ShareAlbumFilter` (now `ShareAccessFilter`) extracted the wrong URI segment as the
          album id (an off-by-one in `uri.split("/")`) and called an `@MainSpaceLimited`
          `shareRepository.findById` — which throws for a guest session (no matching `UserEntity`
          to filter by), so any request through it 500'd for real anonymous guests.
        - `ShareService.getShareDetailsById`/`getSharePassword` (used by the password-login
          endpoint) had the same `@MainSpaceLimited` problem — the password-protected login flow
          itself 500'd for anonymous callers.
        - The non-password-protected flow never established any session at all, so every
          subsequent photo/album fetch was unauthenticated and would have been rejected — this was
          previously flagged here as "needs backend verification"; it was actually broken, not
          just unverified.
        - Frontend/backend key mismatch: `ShareLinkPage.jsx` read `data.selectedAlbumIds`, but the
          backend only ever sent `albumIds`/`albumId` — the non-protected redirect could never
          have worked.
        - Share expiry (`availableUntil`) was computed but never actually checked by the
          controller — an expired link behaved identically to a valid one.
      - **New authorization boundary**: `ShareAccessFilter` now correctly confirms, for every
        `/api/albums/share/**` and `/api/photos/{id}/**` request made under a guest's `ROLE_SHARE`
        session, that the requested album (or the album a requested photo belongs to) matches the
        share the guest actually authenticated against — otherwise 403. Before this, `ROLE_SHARE`
        satisfied Spring Security's blanket `anyRequest().authenticated()` with no further check,
        so (bugs above aside) a guest could in principle reach any album/photo by UUID.
      - **Guest lookups deliberately bypass the space filter** without touching
        `MainSpaceAspectConfiguration` (left alone, as elsewhere in this codebase): added
        `ShareRepository.findByIdIgnoringSpace`/`findByUserMd5AndName`,
        `AlbumRepository.findByIdIgnoringSpace`, and `AlbumService.getAlbumPhotosForShare`/
        `getAlbumPhotoForShare`, kept deliberately separate from the still-`@MainSpaceLimited`
        `getAlbumPhotos`/`getAlbum` the authenticated album-detail view uses — reusing the
        filtered versions for guests would 500 them; reusing the unfiltered versions for real
        users would remove their space isolation.
      - **Session-clobbering guard**: if the browser already has a real logged-in session (e.g.
        the owner previewing their own link in the same tab), `ShareController` now skips
        establishing a `ROLE_SHARE` session instead of silently replacing the real login
        (`isGuestOrAnonymous` check) — an issue that would otherwise have logged owners out of
        their own account just by visiting their own share link.
      - **New backend capability**: password removal — `PUT /api/share/{id}` with `{ "password":
        null }` (`ShareService.updateSharePassword`), ownership-checked the same way
        `deleteShare`/`getShareById` already are.
      - **New frontend**: `SharedAlbumView.jsx` replaces `ShareLinkPage.jsx`/`ShareLoginPage.jsx`
        (deleted) — one component handling both the album-grid and single-photo routes,
        deliberately self-contained (never touches `isAuthenticated`/`useAuth.js` or
        `/albums/:albumId`, unlike the old design). Reuses `PhotoGrid.jsx` read-only for the grid;
        `SharedLightbox.jsx` (new, minimal — prev/next/close only, no edit controls) for the
        single-photo view. `/share-links` (previously a placeholder) is now
        `ShareLinksManagement.jsx` + `hooks/useMyShares.js` — lists owned shares with their full
        URL, a "Remove Password" action, and delete.
      Verified via `javac` compilation (whole backend, clean) and the full frontend test suite
      (78/78 pass, unaffected by this change). Not verified against the live app from this shell
      (no `mvn`/running backend available here) — worth a real click-through: create a link, open
      it in a private window, try the password flow, try removing the password, try deleting it.
- [x] **Follow-up fixes to the share-link feature above**, reported after the fact from a live
      click-through:
      - **Bug fix**: `hooks/useMenuSelection.js` was still POSTing to `/api/photos/selected` on
        every `/share/**` navigation — pointless (guests have no header/menu at all, see
        `SharedAlbumView.jsx`) and actively broken (that endpoint requires a real authenticated
        user; a guest's `ROLE_SHARE` session makes it 500). Now returns early for any path
        starting with `/share`.
      - **Bug fix (the actually-reported one)**: `hooks/usePhotoData.js`'s `loadPhotos` didn't
        recognize `/share/**` paths either, so it fell through to the default `'library'` branch
        and fetched `/api/photos?page=0&size=20&sort=dateTaken,desc` — same problem as above,
        another authenticated-only endpoint hit uselessly (and failing) for guests, on every
        share-view navigation. Now returns early for `/share/**` the same way; share views fetch
        their own photos via `hooks/useSharedAlbumPhotos.js` against a separate, guest-scoped
        endpoint, so this hook has nothing to do there. Added a regression test
        (`usePhotoData.test.js`: "does not fetch anything for a /share/** path").
      - **UX fix, `CreateShareLinkModal.jsx`**: the "Available for (days)" field only had a
        placeholder, which disappears the moment you type a number — leaving the field's unit
        completely unlabeled. Added a persistent label. While in the file (previously the only
        modal with zero `useLocale` usage), localized it and pre-fill the link name field with a
        slugified version of the album name (only when empty, so it never overwrites a manual
        edit) so users usually don't have to type one at all.
      Verified via the full frontend test suite (79/79 pass) and a production build.
- [x] **Bug fix (reported live, with the stack trace)**: `org.springframework.dao.
      InvalidDataAccessApiUsageException: detached entity passed to persist:
      org.iphoto.entities.PersonEntity`, thrown from `PersonRecognitionService.updatePerson`
      (`personRepository.save(person)`). Root cause: `PersonEntity` extends `BaseEntity`, which
      implements `Persistable<UUID>` via a transient `isNew` flag (defaults `true`) — every other
      `BaseEntity` subclass (`ShareEntity`, `AlbumEntity`, `SpaceEntity`) flips it to `false` via
      matching `@PrePersist`/`@PostLoad` callbacks, but `PersonEntity` never did. So `isNew()`
      returned `true` forever, even for a person freshly loaded from the DB — meaning
      `personRepository.save()` always called `persist()` instead of `merge()`. That's harmless
      for a genuinely new person (`PersonRecognitionService.addPerson`, the only construction
      site), but breaks the moment an *existing* person gets saved from a different transaction
      than the one that loaded it — exactly `updatePerson`'s situation, since
      `PersonRecognitionService` is class-level `@Transactional(REQUIRES_NEW)`. Fixed by adding the
      same two callbacks (`@PrePersist`/`@PostLoad` setting `isNew = false`) already present on
      every sibling entity. Verified via `javac` compilation (whole backend, clean); not verified
      against the live app from this shell (no `mvn`/running backend available here) — worth
      confirming the actual flow that hit this (a face getting matched to an existing person via
      `FaceRecognitionService`) no longer throws.
- [x] **Feature**: rename/map a face's person from the Lightbox, merge persons, unlink a face from
      its person, and the exclusion mechanism that makes unlinking stick. See specification.md
      §4.3 (Persons View) for full design detail; summary of the moving parts:
      - **`FaceService.setFacePerson`** (`PUT /api/faces/{id}/person`, `{ name, confirmMerge }`) —
        the Lightbox's face label is now clickable/editable. No collision with a *different*
        person: renames the face's current person outright (all their faces relabel — a typo
        fix), or creates a brand-new person if the face had none yet. Collision with a different
        person: returns `409` with that person's `PersonDTO` instead of applying anything;
        `Lightbox.jsx` shows a `mergePrompt` popup (existing person's cover face next to this
        face's own thumbnail) and resubmits with `confirmMerge: true` if confirmed, which moves
        *only this one face* — this was explicitly corrected mid-conversation: the user's first
        answer said a full-person merge, then corrected it to "only this face links to the new
        person; only a persons-merge moves all faces."
      - **`FaceService.unlinkFaceFromPerson`** (`DELETE /api/faces/{id}/person`) — new `Unlink2`
        button next to a face's name in the Lightbox (shown only when it has one), clears just
        that face's person while keeping the `FaceEntity` itself. Narrower than the pre-existing
        `unlinkPersonFromPhoto` (unlinks every face tying one person to one photo).
      - **`PersonRecognitionService.mergePersons`** (`POST /api/faces/persons/merge`, `{
        personIds: [id1, id2] }`) — `/persons` grid gained a `.check-circle` selection overlay per
        person and a merge action bar. Direction is not caller-chosen: the person with fewer faces
        always merges into the one with more (ties arbitrary), keeping the target's name/cover
        face; the emptied source is deleted.
      - **`FaceLinkExclusionEntity`** (new `face_link_exclusions` table) — the piece that makes
        unlinking actually stick. Every detach path (both unlink actions above, and the
        "move only this face" branch of a collision-merge) records a `(face, excludedPerson)` row.
        `FaceRecognitionService.groupFacesForUser` now loads exclusions for the current batch of
        ungrouped faces once per run and filters each face's candidate person list before
        matching — so the ~30s automatic re-grouping pass can never silently reattach a face to a
        person the user explicitly removed it from, while the face stays free to match a
        *different* person or seed a brand-new one. Deliberately *not* recorded for
        `mergePersons` — merging is "these are the same person," not a correction, so future
        auto-matching against the target should freely include the moved faces.
      - Shared plumbing: `FaceService`'s private `detachFaceFromPerson`/`attachFaceToPerson`
        helpers centralize exclusion bookkeeping + cover-face recalculation so all four call sites
        (rename-collision, unlink-face, unlink-person-from-photo, and the new-person-creation
        branch of setFacePerson) stay consistent; `PersonRepository.findByNameIgnoreCase` (new,
        `@MainSpaceLimited`) backs the collision check.
      Verified via `javac` compilation (whole backend, clean) and the full frontend test suite
      (79/79 pass, unaffected) plus a production build. Not verified against the live app from
      this shell (no `mvn`/running backend available here) — worth a real click-through: rename a
      face to a new name, rename one to a name that collides and confirm the merge popup actually
      moves just that face, unlink a face and confirm the next face-recognition pass doesn't
      immediately reattach it to the same person, and merge two people from `/persons`.
- [x] **Bug fix (reported live)**: renaming/unlinking a face's person from the Lightbox while
      viewing a person's photo grid (`PersonDetail.jsx`) didn't refresh that grid — navigating back
      to the person page still showed the stale photo list (e.g. a photo that no longer matched
      the person, since its only face was just reassigned elsewhere, kept showing). Root cause:
      `Lightbox.jsx` had no way to trigger the app-level `refreshAndMaintainScroll` (the same
      function `handleUpdateDate`/`handleEditPhoto` already call, and that `PersonDetail.jsx`'s
      pre-existing per-photo unlink button already used) — it simply wasn't threaded through
      `LightboxWrapper.jsx` as a prop. Fixed by adding `refreshAndMaintainScroll` to `App.jsx`'s
      `<LightboxWrapper>` call, forwarding it through `LightboxWrapper.jsx` to `<Lightbox>`, and
      calling it after `submitFacePerson`, `handleUnlinkFacePerson`, and (for the same reason)
      `handleRemoveFace` all succeed. Verified via the full frontend test suite (79/79 pass,
      unaffected) and a production build; not verified against the live app from this shell.
- [x] **UI polish, `PersonStrip.jsx`**: two requested changes to the person strip shown above
      `PersonDetail.jsx`'s photo grid:
      - ~~Only named people are listed now~~ — first attempt filtered unnamed people out of the
        strip entirely; corrected right after (reported live: "faces don't show anymore... only
        named face is showing"). The actual rule is narrower: show every person, same as before,
        just render no `<span>` label under an unnamed one instead of the old "Who is this?"
        placeholder text — `person.name && <span>{person.name}</span>}`. `alt`/`aria-label` still
        fall back to `unknownPerson` for accessibility even though nothing renders visually.
      - Made the strip `position: sticky` (same `top`/z-index convention `.day-header` already
        uses relative to `.header`) so it stays visible while the grid underneath scrolls, instead
        of scrolling away with it. This meant `.day-header` (shared by every `PhotoGrid.jsx`
        consumer) would otherwise stick at the exact same offset the strip now occupies and render
        hidden behind it for as long as that day group is on screen — fixed with a `:has()`-scoped
        override (`.content-body:has(> .person-strip) .day-header`) that only pushes the day
        header down by the strip's own height when a person-strip actually precedes it, verified
        pixel-accurate (no gap/overlap) against a reproduction using the real compiled CSS.
      Verified via the full frontend test suite (79/79 pass) and a production build; the sticky
      positioning/offset math specifically was verified with a Playwright reproduction against the
      real built CSS (screenshotted + measured `getBoundingClientRect()`), not the live app itself.
- [x] **Bug fix (reported live, with the stack trace)**: `org.hibernate.PropertyValueException`-
      style `... persistent instance references an unsaved transient instance of
      'org.iphoto.entities.PersonEntity' (save the transient instance before flushing)`, thrown
      from `FaceService.attachFaceToPerson` (called by `setFacePerson`'s "no collision, face had
      no person yet" branch — naming a previously-unassigned face creates a brand-new person).
      Same family of issue as the earlier `TransientObjectException` in
      `PersonRecognitionService.addPerson`, but a different trigger: `attachFaceToPerson` sets
      `face.person = newPerson` (still transient at that point) and then calls
      `faceLinkExclusionRepository.deleteByFaceIdAndExcludedPersonId` — a `@Modifying` bulk-delete
      query, which forces Hibernate to auto-flush the session *before* running it. The explicit
      `personRepository.saveAndFlush(newPerson)` `setFacePerson` already had ran too late — *after*
      `attachFaceToPerson` returned, by which point the auto-flush from the delete query had
      already fired with `face.person` pointing at a transient entity. Fixed by moving the
      `saveAndFlush` to *before* `attachFaceToPerson` is called, so `face.person` only ever gets
      pointed at an already-persisted `PersonEntity`. Verified via `javac` compilation (whole
      backend, clean) and the full frontend test suite (79/79 pass, unaffected — backend-only
      change); not verified against the live app from this shell — worth confirming the exact flow
      that hit this (naming a previously-unnamed face in the Lightbox) no longer throws.
- [x] **Security fix**: `GET /api/faces/photo/{id}` (`FaceController.getFacesForPhoto` →
      `FaceService.getFacesForPhoto` → `FaceRepository.findByPhotoId`) was not scoped to the
      current user's space at all — any authenticated user could fetch face thumbnails (including
      the base64-encoded crop images) for *any* photo id in the system, not just their own.
      Confirmed live: an `admin` session got a 404 on `/api/photos/{id}` for a photo it didn't own
      but a 200 with real face data on `/api/faces/photo/{id}` for the same id. Root cause:
      `FaceEntity` has no `space_id` column (it belongs to a `PhotoEntity`, which has one), so it
      can't be `@MainSpaceLimited` the way `AlbumRepository`/`PhotoRepository`'s space-scoped
      methods are — ownership has to be checked explicitly. The controller already fetched
      `currentUser` (apparently intending to use it) but never actually passed it anywhere, so
      nothing enforced it. Fixed by threading the user through to the service and checking
      `photo.getSpace().getUser().getId().equals(user.getId())` before returning anything — same
      pattern `PhotoController.getPhoto` already uses for the identical problem.
- [x] **Bug fix**: deleting a share link deleted its album too. `ShareEntity.album` was mapped
      `@ManyToOne(cascade = CascadeType.REMOVE)` — backwards: that cascades entity removal *from*
      the share *onto* the album whenever the share is deleted, so `ShareService.deleteShare`
      (just a plain `shareRepository.delete(share)`, nothing album-specific in it) took the album
      down with it, including if other shares still pointed at it or the user just wanted to stop
      sharing without losing the album. The already-present `@OnDelete(action =
      OnDeleteAction.CASCADE)` (DB-level `ON DELETE CASCADE` on the FK) is the *correct* direction
      — if the album is deleted, this now-dangling share row should go — and was left as-is; only
      the JPA `cascade = CascadeType.REMOVE` attribute was removed.
      Verified via `javac` compilation (whole backend, clean) and the full frontend test suite
      (79/79 pass, unaffected — both backend-only changes); not verified against the live app from
      this shell.
- [x] **Bug fix**: recurring people went completely ungrouped when other people's photos were
      interleaved in between their occurrences, even close together in time. Root-caused against 3
      real assets the user pointed to (`/asset/0e955908-...`, `/asset/14f173ff-...`,
      `/asset/cc68b7ab-...`, all one gathering, 3 photos ~41 minutes apart, 2 people in each — 6
      faces total, all `personName: null`). Confirmed via a direct DB pull of the embeddings
      (`docker exec georges-db-1 psql ...`) that the correct cross-photo pairs scored 0.91–0.98
      cosine similarity (well above `THRESHOLD = 0.60`), so the 2 real people were never being
      compared at all, not scored-and-rejected.
      Cause: `clusterByTemporalProximity` sorted **all** of a user's ungrouped faces (every photo,
      every unrelated person) by `dateTaken` and compared each face only against the next
      `TEMPORAL_NEIGHBOR_WINDOW` (10) faces **by position** in that global list — not by actual
      elapsed time. Between the two occurrences here sat 13 faces from *other* people's photos
      taken in the 2 minutes in between, pushing the second occurrence outside the position window
      despite being only 41 minutes away. Each real person then fragmented into disconnected
      2-face components, and `MIN_NEW_PERSON_CLUSTER_SIZE` (3) silently dropped every one of them
      — this is very likely the general-case explanation for "these are obviously the same person
      but never got grouped" whenever a session/event has several different people in it.
      Fix: `clusterByTemporalProximity` now bounds its comparison window primarily by a new
      `MAX_NEW_PERSON_CLUSTER_GAP` (24h elapsed time) instead of position; `TEMPORAL_NEIGHBOR_WINDOW`
      is still used, just multiplied up (`* 20`) into a `maxCandidatesPerFace` position cap kept
      only as an O(n^2) cost safety net for a dense same-day event. Left `nearestTemporalNeighbors`
      (the *existing*-person matching path) untouched — that one already windows over one specific
      person's own faces, not the global cross-person list, so it was never affected by this bug.
      Verified two ways: (1) `javac` compilation (whole backend, clean); (2) simulated the exact
      before/after algorithm in Python against the real embeddings pulled from the DB for all 50 of
      this user's ungrouped faces — before the fix, 0 of the 3 photos' 6 faces formed any surviving
      cluster; after, all 6 land in two clusters (sizes 4 and 3) that both clear
      `MIN_NEW_PERSON_CLUSTER_SIZE`. Not yet verified end-to-end against the live app — the running
      backend is IntelliJ/debugger-managed (not started from this shell), so needs a manual
      restart before `TaskWorkerManager`'s ~30s face-recognition task picks up the new code and
      re-processes these faces into real `PersonEntity` rows.

## Open / Not Yet Done

- [ ] **Feature**: detect blurry faces. Confirmed feasible, not yet implemented — no design
      decision made yet on what to DO with the signal (see below), just logging the "can this be
      done" question the user asked.
      - **Technique**: variance of the Laplacian (Pech-Pacheco et al.) — grayscale the face crop,
        run a Laplacian edge filter, take the variance of the result. Sharp faces have lots of
        fine-edge high-frequency content -> high variance; blurry ones lack it -> low variance,
        below some threshold (commonly ~100, needs empirical tuning against this app's actual
        photos — not something to hardcode from a blog post without checking real data). No ML
        model needed, no new dependency: the app already depends on `org.bytedeco:opencv`/
        `javacv` (`pom.xml`), whose `Imgproc.Laplacian` + `Core.meanStdDev` does this directly; a
        pure-Java version over the `BufferedImage` pixel array would also work if avoiding another
        native call per face is preferred.
      - **Where it hooks in**: `FaceDetectionService.detectAndSaveFaces` already extracts
        `subImage` (the raw face crop, straight from the detector's bounding box) *before*
        `FaceAligner.align`/resizing it down for the embedding model — cheapest point to compute a
        blur score, since the exact crop pixels are already in memory and no extra I/O is needed.
        Would need a new column on `FaceEntity` (e.g. `blurScore` or `isBlurry`) to persist it.
      - **Open design question — what to actually do with a blurry flag** (this is what's blocking
        implementation, not the detection itself): user's explicit leaning is (e) — reject the
        face outright at detection time, same place `MIN_FACE_SIZE`/`SMALL_FACE_MIN_CONFIDENCE`
        already reject too-small/low-confidence detections (`FaceDetectionService.
        detectAndSaveFaces`'s `continue` checks) — a blurry crop would just never become a
        `FaceEntity` at all, no new column, no UI changes anywhere. User explicitly said they need
        more time to decide before committing to this though, so **not yet implemented** — do not
        build this on the next session without re-confirming, in case the decision changes.
        Other options that were on the table before that leaning, kept here in case (e) gets
        reconsidered: (a) never pick a blurry face as a person's `coverFace`
        (`PersonRecognitionService.recalculateCoverFace`) — cheapest, safest, no real downside,
        and notably NOT mutually exclusive with (e) (a face just above whatever blur-reject
        threshold could still be too blurry to want as a cover); (b) a visual "blurry" badge on
        face thumbnails (Lightbox's "Faces Detected" section, Persons/PersonDetail views) — moot
        if (e) is chosen, since a rejected face is never saved to have a badge on; (c) a
        filter/bulk-clean toggle on the Persons page for deleting already-saved bad detections —
        also largely moot under (e), since new blurry ones would never accumulate in the first
        place (existing pre-(e) blurry faces already in the DB could still want cleanup though);
        (d) deprioritizing blurry faces in `FaceRecognitionService`'s similarity matching — highest
        risk/complexity of the four, and irrelevant if (e) means blurry faces never reach matching
        at all.
- [x] **Feature**: whole-person rename now merges on a name collision, and the Person page title
      became editable. User's spec: renaming a person to a name that doesn't exist yet just
      renames it; renaming to a name that already belongs to one or more OTHER people prompts a
      picker (one face per candidate) to choose which (if any) to merge in. Distinct from the
      existing Lightbox single-FACE rename flow (`FaceService.setFacePerson`), which reassigns one
      face only — this is a whole-PERSON rename, folding in every face of whichever candidates get
      picked.
      - **Backend**: `FaceService.updatePerson` (previously a blind rename, no collision check at
        all) replaced with `renamePerson(personId, name, confirmed, mergeWithPersonIds)`. Unconfirmed
        + a collision -> throws new `PersonNameCollisionsException` (plural — sibling of the
        existing singular `PersonNameCollisionException` used by the single-face flow; a whole-person
        rename can collide with *several* existing people, since this app doesn't enforce name
        uniqueness at the DB level, unlike the single-face flow's single-Optional lookup). Confirmed
        -> renames, then folds in every id in `mergeWithPersonIds` (possibly none — a deliberate
        "rename anyway" choice) via new `PersonRecognitionService.mergeInto(targetId, sourceId)`.
        `mergeInto` is a fixed-direction sibling of the existing `mergePersons` (the Persons-grid
        multi-select merge, which picks direction by face count) — here the renamed person is
        ALWAYS the keeper, never chosen by count, since the user explicitly picked it by renaming
        it. Both now share a `mergeFacesIntoTarget` private helper (extracted from `mergePersons`'s
        previous body) instead of duplicating the move-faces/delete-source logic.
        New `PersonRepository.findByNameIgnoreCaseAndIdNot` (plural-returning, unlike the existing
        singular `findByNameIgnoreCase` used by the single-face flow) finds the collision
        candidates. `FaceController`'s `PUT /api/faces/person/{id}` changed from `void` +
        `PersonDTO` body to `ResponseEntity<?>` + new `PersonRenameRequest` body (`{name, confirmed,
        mergeWithPersonIds}`, mirroring `FaceRenameRequest`'s `confirmMerge` shape) — 409 on
        collision returns the candidates' full `PersonDTO`s (cover face + photo count included) via
        new `FaceService.getPersons(ids)`.
      - **Frontend**: new shared `hooks/usePersonRename.js` (the fetch/collision-state machine) and
        `components/PersonMergePrompt.jsx` (the picker modal — one card per candidate showing its
        own cover face + photo count, since they all share the exact name that collided, so a name
        label wouldn't help tell them apart) — both used by `Persons.jsx`'s existing inline rename
        (which had ZERO collision handling before this — a blind PUT that could silently create
        duplicate-named people) and the newly-added `PersonDetail.jsx` page-title rename (click the
        `<h2>`, same interaction pattern as Lightbox's metadata-field edits). `useEntityById.js`
        gained an exposed `setEntity` so `PersonDetail.jsx` can reflect a rename/merge immediately
        without a full refetch. `PersonStrip.jsx` gained an optional `refreshToken` prop
        (`PersonDetail.jsx` bumps it after a successful rename/merge) since a merge can delete a
        person from that strip's list or change someone's cover photo — its own internal
        `usePersons()` call has no way to know about that otherwise. A confirmed merge can also
        pull new photos into the person being viewed, so `PersonDetail.jsx` calls the existing
        `refreshAndMaintainScroll` afterward too, same as every other face/person mutation on that
        page already does.
      Verified: clean `javac` compile; `npm run build`; full frontend suite 87/87 pass (added 5 new
      `usePersonRename.test.js` tests — plain rename, collision surfaces candidates without
      applying, confirmed merge sends the right body, confirmed-with-zero-selected still renames,
      cancel applies nothing — none of the touched components are on CLAUDE.md's
      always-test-after-touching list, but the new hook carries meaningful enough logic to warrant
      its own coverage regardless). Not yet verified against the live app — backend restart still
      pending from earlier entries in this file.
      Flagged, not fixed (pre-existing, not introduced by this change): `GET`/`PUT
      /api/faces/person/{id}` (and several sibling `/api/faces/person/**` endpoints — `getPerson`,
      `getPersonFaces`, `getPersonPhotos`) declare a `getCurrentUser()`/`UserEntity currentUser` that
      is never actually used to scope the lookup — `PersonRepository.findById` isn't
      `@MainSpaceLimited`. Same category of gap as the `/api/faces/photo/{id}` issue fixed earlier
      in this file, not fixed here to avoid changing behavior of several unrelated endpoints beyond
      what was asked — worth a dedicated security pass.
- [x] **Feature**: yellow circle around the selected person. User confirmed the target was
      `PersonStrip.jsx`'s currently-viewed person (option 1 of the two flagged), not
      `Persons.jsx`'s merge-selection overlay. No JSX change needed — the `.active` class was
      already applied to the right item; only `App.css` changed. `.person-strip-item.active img`/
      `.person-strip-placeholder` used to just recolor the existing 2px border to `var(
      --primary-color)` (blue); replaced with `box-shadow: 0 0 0 3px rgba(255, 255, 0, 0.85)` (the
      same yellow as the Lightbox's `.face-box` overlays, reused for one consistent "highlighted"
      color across the app) — box-shadow rather than border-color so the ring traces the circular
      avatar exactly, with a hairline gap, instead of just recoloring the border in place.
      **Amended**: the yellow was too low-contrast to actually notice against the strip's light
      background (user feedback: "we can't see yellow circle") — changed to solid `#ff3b30` red
      and thickened the ring from 3px to 4px.
      Verified: `npm run build` and full frontend suite 82/82 pass (CSS-only change; PersonStrip.jsx
      itself wasn't touched, so it's not on CLAUDE.md's always-test-after-touching list, but ran the
      suite anyway). Not verified against the live app (backend restart still pending from earlier
      entries; this one's frontend-only so a plain page refresh would show it once the built
      frontend is served, but wasn't spot-checked from this shell).
- [x] **Feature**: "Memories" (6mo/1yr/3yr/10yr-ago) and a month/year browse list at the top of
      the Library page, built around one shared month/year index — see the design discussion with
      the user first, then what actually got built:
      - **Backend, month/year index**: `GET /api/photos/timeline` (`PhotoController` →
        `PhotoService.getTimeline` → `PhotoRepository.findMonthlyPhotoCountsBySpaceId`), Main
        Space only. Returns every `TimelineMonthDTO{year, month, count}` bucket that has at least
        one non-garbage photo, newest first — a JPQL `GROUP BY` with `EXTRACT(YEAR/MONTH FROM
        dateTaken)` (Jakarta Persistence 3.1, portable — not a Postgres-specific function), `CAST`
        to integer so the returned type matches the DTO constructor regardless of a given
        dialect's native `EXTRACT` numeric type. Months with zero photos are simply absent, not
        returned with count 0. Verified the aggregate SQL directly against Postgres
        (`docker exec georges-db-1 psql ...`) before trusting the JPQL translation.
      - **Backend, jump-to-month**: `GET /api/photos` gained an optional `jumpToMonth=yyyy-MM`
        param (`PhotoController.parseJumpToMonth`) — malformed/absent input is treated as "no
        jump" (logged, not a 400), since it's an optional UX param on an otherwise normal listing
        endpoint. When present, it's converted to "first instant of the following month" and
        passed to a new `PhotoService.getPhotos(spaceId, pageable, beforeExclusive)` overload
        backed by `PhotoRepository.findBySpaceIdAndIsGarbageFalseAndDateTakenLessThan` — same
        dedup-against-duplicates logic as the normal feed (extracted into a shared
        `buildDedupedPage` helper), just with an extra upper time bound so the returned page starts
        exactly at the requested month.
      - **Frontend, jump navigation**: `usePhotoData.jumpToMonth(year, month)` — resets the grid
        to page 0 and refetches with `jumpToMonth` set, the same reset-and-reload the initial page
        load already does. Bidirectional scrolling past the jumped-to point was a known limitation
        here — **now fixed, see the follow-up "Done" entry below**.
      - **Frontend, UI**: new `LibraryTimeline.jsx` (fed by new `useMonthlyTimeline.js`, which does
        the single `/api/photos/timeline` fetch both halves below share), rendered above `PhotoGrid`
        only when `selectedMenu === 'library'` (Main Space, matches the Album-vs-Space distinction
        in specification.md) — not on Album/Space/Person/garbage views.
        - Memories strip: for each of the 4 offsets (6/12/36/120 months back), computes the target
          `{year, month}` and looks it up in the index directly — an offset with no match for that
          month simply isn't rendered, no separate query.
        - Browse-by-month list: years as clickable pills: clicking a year expands/collapses its
          months inline (no popover), clicking a month calls `jumpToMonth`.
      Verified: clean `javac` compile; `npm run build` (Vite) succeeds; full frontend suite
      80/80 pass (added one `usePhotoData.test.js` regression locking in `jumpToMonth`'s page-0
      reset + `jumpToMonth=yyyy-MM` query param, since that hook is on CLAUDE.md's
      always-test-after-touching list). Not yet verified against the live app — the running
      backend is IntelliJ/debugger-managed (not started from this shell) and hasn't been restarted
      to pick up these changes.
      Deliberately not built yet (kept as follow-up, not silently dropped):
      - Album/Space browse-by-month scope (the index/jump endpoints are Main Space/Library only
        for now, per the user's explicit ask — "Memories should show at the top of the library
        page"). Extending to Album/Space would need a per-scope index query and a jump endpoint on
        those routes too.
      - The browse list is a simple expand/collapse year→month list, not the Google Photos-style
        drag/hover scrubber minimap originally sketched — the user's "we need a list of all our
        months/year with assets first" framed this as the actual near-term ask; a fancier scrubber
        UI can build on the same index later if wanted.
- [x] **Follow-up to the entry above**: bidirectional scrolling past a jumpToMonth landing point,
      and a cover thumbnail on each Memory card — both explicitly requested next.
      - **Cover thumbnail**: `TimelineMonthDTO` gained a `coverPhotoUrl` field. The month/year
        index query (`PhotoRepository.findMonthlyPhotoCountsWithCoverBySpaceId`, renamed from
        `findMonthlyPhotoCountsBySpaceId`, which it replaces outright) switched from portable JPQL
        to native Postgres SQL with window functions (`count(*) over (partition by ...)` for the
        count, `row_number() over (partition by ... order by random())` picking each month's
        cover) — one query still gets both count and cover, no N+1. Cover selection was initially
        "most recent photo that month" (deterministic); the user clarified Memories should show "a
        random photo from the past", not always the same one — changed to `order by random()`, a
        fresh pick every call. Deliberately native/Postgres-specific: this app is Postgres-only in
        practice (`application.yml` hardcodes the driver), and picking "one random row per group"
        alongside its aggregate isn't expressible in portable JPQL. `PhotoService.getTimeline`
        builds the cover URL the same cache-busted way `PhotoMapper` already does for every other
        thumbnail (`/api/photos/{id}/thumbnail?v={fileHash}`). `LibraryTimeline.jsx`'s memory cards
        render this as a background image with a gradient-overlay caption (label/date/count),
        replacing the old text-only card.
      - **Bidirectional scrolling**: `usePhotoData.js` gained a cursor-based pagination path used
        only after `jumpToMonth`, alongside the existing plain page-number path (unchanged, still
        default for normal top-of-Library/Album/Space/Person/Garbage browsing):
        - Cursor value: reused `Photo.fullDateTime` verbatim (discovered it's already
          `ISO_LOCAL_DATE_TIME` — see `PhotoMapper` — despite an outdated model comment claiming a
          display format; no new backend field needed, it round-trips through
          `LocalDateTime.parse` exactly). Sent back as `beforeDate` (older direction) / `afterDate`
          (newer direction, the actually-new capability here).
        - Backend: `GET /api/photos` gained `beforeDate`/`afterDate` params
          (`PhotoController.parseCursor`) alongside the existing `jumpToMonth` — `beforeDate` reuses
          the `getPhotos(spaceId, pageable, beforeExclusive)` overload already built for
          `jumpToMonth` (just fed an exact photo timestamp instead of a month boundary) for continued
          older-direction paging; `afterDate` is genuinely new — `PhotoRepository.
          findBySpaceIdAndIsGarbageFalseAndDateTakenGreaterThan` + new
          `PhotoService.getPhotosAfter` (fetches ascending — closest-to-cursor first, not oldest —
          then reverses the page's `content` back to newest-first before returning, so every
          response the frontend sees is ordered the same way regardless of direction).
        - Frontend: found and fixed a real bug from the first pass while building this — continuing
          to scroll down (older) after a jump was silently broken, because the page>0 effect still
          incremented a raw page number, which stopped corresponding to any consistent slice once
          the feed had been reset mid-stream by a jump. Fixed by switching that effect to
          cursor-based `loadOlder()` once `jumpToMonth` has been called (tracked via a `jumpedRef`),
          while leaving normal (never-jumped) browsing on the original numeric path untouched.
          `loadNewer()` is the new newer-direction counterpart, prepending results and exposed
          alongside a `hasNewer` flag (optimistically true right after a jump, self-correcting to
          false on the first `loadNewer` call that comes back empty).
        - `PhotoGrid.jsx` gained a top sentinel (mirroring the existing bottom one) wired only when
          the caller passes `onLoadNewer`/`hasNewer`/`mainContentRef` — Library route only, via new
          props on the existing component, so Album/Space/Person/Garbage/duplicates usages are
          unaffected. Also handles scroll-position compensation: prepending photos above the
          current viewport would otherwise visually yank whatever the user was looking at downward
          by the prepended content's height, so a `useLayoutEffect` measures `scrollHeight` before
          and after and adjusts `mainContentRef.current.scrollTop` by the delta, synchronously
          before paint.
        - The user separately confirmed the intent behind this design: the standard downward
          infinite scroll must not be *replaced* by a distinct "jumped" mode, only *extended* to
          also work upward. That's what the above actually does — never-jumped browsing runs
          through the exact same `hasMore`/`setPage`/bottom-sentinel path as before, byte-for-byte
          unchanged; a jump doesn't swap in a different component or scroll mechanism, it just
          starts the same `PhotoGrid`/`usePhotoData` pair from a different point and (only from
          then on) also allows the *same* grid's top sentinel to load newer content.
      Verified: clean `javac` compile (including a direct Postgres check of both the aggregate
      cover-thumbnail SQL and the plain `GROUP BY` it replaced, run via `docker exec georges-db-1
      psql` before trusting the JPQL/native translation either time); `npm run build`; full frontend
      suite 82/82 pass (added 2 new `usePhotoData.test.js` regressions: the older-direction cursor
      switch after a jump, and `loadNewer`'s cursor + prepend behavior — both on CLAUDE.md's
      always-test-after-touching list, along with `PhotoGrid.jsx` which was also touched). Not yet
      verified against the live app for the same reason as the entry above — backend restart still
      pending.
- [ ] **Feature**: on a person's photo grid (`PersonDetail.jsx` → `PhotoGrid`), overlay a yellow
      box (reuse the `.face-box` style from `Lightbox.jsx`) on each thumbnail marking exactly where
      *that person's* face is in the photo — like the existing Lightbox face-box overlay, but on
      the grid, and scoped to the one person being viewed (not every detected face). Needs backend
      support: neither `Photo` nor the `getPersonPhotos` response currently carries the face's
      x/y/width/height for that person/photo pair — `FaceDTO` has the box but no `photoId` to join
      on, and `getPersonFaces`/`getPersonPhotos` are two separate paginated endpoints, not
      guaranteed to be in step. Cleanest fix is probably to extend `PersonController`'s
      `getPersonPhotos` path so each returned `Photo` (or a small new DTO wrapping it) carries that
      person's face box for it directly, computed server-side, rather than the frontend trying to
      stitch two separate paginated lists together. Then in `PhotoGrid`, the box needs to scale from
      the face's coordinates (recorded against the photo's *full* resolution) down to whatever size
      the grid thumbnail actually renders at — same scaling math the Lightbox already does in
      `recalculateImageSize`, reusable if extracted.
- [ ] **Feature**: Lightbox face-box hover refinement. Hovering the "Faces Detected" zone already
      shows every detected face's box on the image (`onMouseEnter`/`onMouseLeave` on that section
      toggles `showFaces`, rendered at `Lightbox.jsx:573`) — that part already works. What's
      missing: hovering one specific `.face-item` in that list should narrow the display down to
      *only* that face's box (others hidden), not all of them. Needs: a `hoveredFaceId` state set
      on each `.face-item`'s own `onMouseEnter`/`onMouseLeave` (in addition to, not instead of, the
      existing zone-level `showFaces`); the box-rendering condition becomes roughly `showFaces &&
      (!hoveredFaceId || face.id === hoveredFaceId)`, so leaving a specific face (but still inside
      the zone) falls back to showing all boxes again.
- [x] **Feature**: the "×N similar" badge (`PhotoGrid.jsx`) now opens a popup instead of expanding
      the cluster inline. User's spec, gathered over two messages: modal width 90% of the browser
      viewport; an X top-right closes it; 2 columns, no pagination (every member renders at once,
      the grid area itself scrolls if that's a lot of photos); each image's width follows the
      2-column layout (~45% of the viewport width each, out of the 90vw modal), height NOT fixed —
      follows that width via the image's own natural aspect ratio, so photos of any orientation
      don't get cropped/distorted; the existing per-member move-to-garbage icon carries over.
      - New `components/DuplicateClusterModal.jsx` (`item`, `onClose`, `handleMoveToGarbage`
        props) — deliberately its own `.duplicate-modal-content` CSS class rather than reusing the
        shared `.modal-content` (fixed 400px/max-width 90%, wrong shape for a wide 2-column grid).
      - **Amended**: initially rendered each member via its thumbnail (`m.url`) — the whole point
        of this popup is comparing near-duplicates closely enough to decide which to keep, and a
        thumbnail isn't sharp enough for that (user: "is it better to use original photos"). Now
        `/api/photos/{id}/original`, same cache-busting-by-`fileHash` pattern `Lightbox.jsx`
        already uses for its own full-size view.
      - **Amended again**: with full originals, a tall/portrait photo could fill the whole
        scrollable grid on its own, making comparison mean constant scrolling (user: "hard to
        compare large photos"). Each image now gets `max-height: 68vh` (80% of the popup's own
        `85vh` cap) + `object-fit: contain` — landscape photos are unaffected (max-height only
        clamps once natural height would exceed it), a clamped tall photo shrinks proportionally
        (letterboxed sides) rather than cropping/stretching.
      - `PhotoGrid.jsx`: removed `expandedGroups`/`toggleGroupExpanded` and the inline
        expand/collapse row (`duplicate-cluster`/`duplicate-cluster-header`/`-row`/
        `duplicate-collapse-btn`, all now dead) entirely. New `openDuplicatePrimaryId` state holds
        just the cluster's *primary photo's id*, not the item object itself — the actual item
        shown in the modal is re-derived from the live `groups` computation every render
        (`Object.values(groups).flat().find(...)`), so moving a member to garbage from inside the
        modal is reflected immediately (member disappears) instead of the modal holding a stale
        snapshot from when it was opened.
      - `PhotoGrid.test.jsx`'s "duplicate grouping" describe block rewritten for the new popup
        (open/close, member list, per-member garbage icon) — the old "select an individual
        duplicate for bulk action" test was dropped rather than adapted, since the modal doesn't
        support selection at all, by design (not something this spec asked for).
      - Removed the now-orphaned `collapseDuplicateGroup` i18n key (en/fr) — nothing references it
        anymore; the X button reuses the existing generic `close` key instead.
      Watch for: this is a different surface from the dedicated `/duplicates` comparison flow
      (`DuplicatePhotoGrid.jsx`) — this badge/cluster appears wherever `PhotoGrid` is used
      (library/album/space/person/shared views) and shows already-known duplicate photos side by
      side, not the deduplication *decision* workflow; the two stay conceptually separate.
      Verified: `npm run build`; full frontend suite 86/86 pass (was 87 — net -1 from dropping the
      now-inapplicable selection test above; `PhotoGrid.jsx` is on CLAUDE.md's
      always-test-after-touching list, ran it accordingly). Not yet verified against the live app —
      backend restart still pending from earlier entries (though this particular feature is
      frontend-only, so it doesn't actually need one to work).
- [x] ~~**Feature**: rename a person directly from the Lightbox's "Faces Detected" section~~ and
      ~~**Feature**: merge two persons into one~~ — both done, see the detailed "Done" entry below
      ("Rename/map a face's person from the Lightbox, merge persons, unlink a face from its
      person, and the exclusion mechanism that makes unlinking stick"). Turned out richer than
      either original note anticipated: renaming a face now distinguishes a plain rename from a
      name-collision merge (with a confirmation popup), and the merge direction question ("what
      happens to the source's name") was resolved by the user as "whichever person has fewer faces
      merges into the one with more, unconditionally" rather than a manual source/target pick.

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
- [x] **Cosmetic, `CreateShareLinkModal.jsx`**: 2 `react/no-unescaped-entities` errors — literal
      `"` characters in JSX text around line 24. Fixed (`&quot;`) while doing the share-link
      rewrite below, since it was a one-line change in a file already being touched.
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
      `Header.jsx`. (`Lock` import in `ShareLinkPage.jsx` is moot — that file no longer exists,
      see the share-link rewrite below.)
- [ ] `LocaleContext.jsx` gets a `react-refresh/only-export-components` warning (exports both a
      component and non-component values from the same file) — cosmetic, affects Fast Refresh
      only, not correctness.
- [x] ~~**Needs backend verification**: `ShareLinkPage.jsx`'s non-password-protected path...~~ —
      confirmed genuinely broken (not just under-verified) and fixed as part of the full
      share-link rewrite below. See specification.md §4.4 for the current design.

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
