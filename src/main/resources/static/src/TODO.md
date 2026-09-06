# Refactor TODO

Running list of refactor/cleanup work on the frontend (`static/src`). Update this file whenever
new work is identified or completed, so nothing gets lost between sessions.

## Done

- [x] **`DuplicateClusterModal.jsx` redesigned — flicker compare instead of a static image grid.**
      User feedback on the equal-size-columns `/duplicates` page ("hard to see differences and
      choose") led to exploring four options (metadata diff strip, auto-suggested keeper badge,
      bigger images + zoom, flicker/slider toggle compare) via an interactive artifact demo. User
      picked flicker as primary, asked for all four combined with flicker highest-precedence — but
      clarified the target was this popup (opened from a "×N similar" badge in `PhotoGrid.jsx`),
      not the separate `/duplicates` page, which they don't use in practice (the popup superseded
      it as their actual workflow). First implementation landed on `DuplicatePhotoGrid.jsx`
      (`/duplicates`) by mistake and was reverted (`git checkout`) once corrected.
      Implementation: the cluster's highest-resolution member (`width*height`, already on the
      `Photo` DTO — no backend change) is the "keeper" (gold star badge), shown flickering in place
      against whichever other member is picked from a thumbnail chip row underneath (click,
      spacebar, or an "auto-flicker" checkbox alternating every 700ms) — generalizes to any cluster
      size, not just pairs, since it's always keeper-vs-one-other rather than a fixed grid. A
      metadata diff table (resolution/file size/camera/date) sits below the stage, differing values
      in red. A `Maximize2` button on the stage — and clicking the keeper's own chip — closes the
      popup and opens the photo in the full Lightbox (`onExpand` prop, wired from `PhotoGrid.jsx`'s
      `setSelectedPhoto`) for actual pixel-level zoom, reusing Lightbox rather than building new
      zoom UI. Kept the per-member trash icon (`handleMoveToGarbage`, already wired from
      `PhotoGrid.jsx`). Modal widened from a fixed `90vw` two-column grid to `min(720px, 90vw)`
      single-column (`.duplicate-modal-body` now scrolls independently under a fixed header). New
      i18n keys (`duplicateShowingKeeper`, `duplicateToggleButton`, `duplicateAutoFlickerLabel`,
      `duplicateKeeperLabel`/`duplicateCompareLabel`, `duplicateResolutionLabel`/
      `duplicateFileSizeLabel`/`duplicateCameraLabel`, `duplicateSinglePhotoNotice`, etc.) added for
      en/fr. New CSS in `App.css` (`.flicker-stage`, `.duplicate-meta-diff`, `.duplicate-chip-row`,
      `.keeper-badge`, ...); removed the now-unused `.duplicate-modal-grid`/`.duplicate-modal-item`
      rules. `PhotoGrid.jsx` is on CLAUDE.md's regression-tested hook list, so `PhotoGrid.test.jsx`
      was updated to match the new DOM (`.duplicate-chip` instead of `.duplicate-modal-item`, flicker
      toggle assertions instead of "every member rendered at once") rather than left broken; added a
      new test for the toggle itself. `npm run build` and `npm test -- --run` (87/87) both clean.
      `DuplicatePhotoGrid.jsx`/`/duplicates` itself is unchanged/reverted to its original static
      side-by-side layout — still exists as a route, just not where this redesign went.

- [x] **`DuplicateClusterModal.jsx` follow-up 2 — cycle all members, stage-level trash/unlink icons.**
      User: the previous pass's "any pair by clicking them in sequence" model was still only ever
      showing "one of two assets even with more than 2" per member visible at a time — confusing
      given a cluster of 3+. Replaced the `shownIndex`/`previousIndex` swap-between-last-two model
      with a single `shownIndex` that cycles through **every** member in order on toggle/space/
      auto-flicker (`(i + 1) % members.length`, wrapping) — direct thumbnail clicks still jump to
      any specific one instantly. Simpler code too (no more previous-index bookkeeping). Added a
      3-member test (`makePhotosWithTripleDuplicate`) asserting the stage visits all three and
      wraps back around, not just alternates between two.
      Also added trash and unlink icons to the bottom-right of the main stage itself (previously
      the trash icon only existed on each small rail thumbnail) — `.flicker-photo-actions`, acting
      on whichever member is currently shown. Trash reuses `handleMoveToGarbage` (already wired).
      **Unlink backend**: the user built this themselves in parallel (`DuplicateController`/
      `DuplicationService`/`DuplicationUniquenessService` — a real data-model change too, replacing
      `DuplicateEntity`'s plain `Set<PhotoEntity> photos` with a `Set<PhotoDuplicateEntity>` join
      entity carrying its own per-photo `status`, expanding `DuplicateStatus` to
      `IN_GROUP/UNLINKED/MOVED_TO_GARBAGE/UNLINKED_BUT_MOVED_TO_GARBAGE`). A first backend attempt
      was made here before that was noticed (`DuplicationUniquenessService.unlinkPhotoFromGroup(UUID)`,
      a `DuplicationService` wrapper, `DELETE /api/photos/duplicates/photos/{photoId}`) and fully
      reverted by hand once told "no backend change on you side" (not `git checkout`, to avoid
      clobbering the user's own concurrent edits to the same files).
      Final contract, per the user: `PUT /api/photos/duplicates/unlink` with body
      `UnlinkPhotoDuplicate{unlinkedPhotoId, duplicatesId}` — `duplicatesId` is every member of the
      group as currently known client-side; `DuplicationService.unlinkPhotoFromGroup` only acts if
      the server's live IN_GROUP set for that photo's cluster still contains all of them (stale-view
      guard), then flips just that one `PhotoDuplicateEntity` to `UNLINKED`.
      Frontend wiring: `usePhotoActions.js` gained `handleUnlinkDuplicate(photoId, groupPhotoIds)`
      (PUT the pair above, then `refreshAndMaintainScroll` — same pattern as `handleMoveToGarbage`),
      threaded `App.jsx` → `PhotoGrid.jsx` → `DuplicateClusterModal.jsx`. The stage's `Unlink2`
      button calls it with `(shown.id, members.map(m => m.id))` — the full member list, including
      the photo being unlinked itself, since the backend check is a `containsAll` (a superset is
      fine). New i18n `failedToUnlinkDuplicate`. `PhotoGrid.test.jsx` gained a test asserting the
      stage's trash/unlink buttons act on whichever member is currently shown, with the correct
      args. `npm run build` and `npm test -- --run` (90/90) both clean.

- [x] **`DuplicateClusterModal.jsx` follow-up — any-pair flicker, side rail, single-photo metadata.**
      User feedback on the first pass above: flicker/metadata only ever compared 2 of the cluster's
      members (keeper fixed as one side); thumbnails should be a side rail, not a row underneath;
      the thumbnail that's highlighted should track whichever photo is actually on stage; and the
      metadata panel should describe only that one shown photo, not a two-column diff.
      Replaced the fixed `keeper`/`compare` pairing with a single `shownIndex` (whichever member is
      currently on the stage — starts on the keeper) plus a `previousIndex` (whichever was shown
      right before it). Clicking *any* thumbnail calls `selectMember(i)`, which displays that member
      immediately and remembers the one it replaced; flicker/space/auto-toggle now swap between
      those last two, so any two members become flickerable just by clicking them in sequence — not
      only keeper-vs-one-other. Thumbnail rail moved from `.duplicate-chip-row` (bottom, horizontal)
      to `.duplicate-chip-rail` (side, vertical, own scroll, row layout under 560px) — the `.active`
      highlight now follows `shownIndex` directly, so exactly one thumbnail is highlighted and it's
      always the one on stage. The two-column `.duplicate-meta-diff` table (keeper vs compare, diff
      cells in red) was replaced with `.duplicate-meta-list` — a plain label/value list for `shown`
      alone, headed by "Suggested keep"/"Comparing" depending on whether the shown member is the
      keeper. Dropped the keeper-chip-click-opens-Lightbox shortcut (inconsistent once every
      thumbnail's job is "show this one") — the `Maximize2` button on the stage is now the only way
      to open the full Lightbox from here. Modal widened to `min(820px, 92vw)` to fit the rail
      alongside the stage. i18n: dropped now-unused `duplicateSinglePhotoNotice`/
      `duplicateSetCompareTitle`, added `duplicateShowMemberTitle` for en/fr.
      `PhotoGrid.test.jsx` updated again (`.duplicate-rail-thumb` instead of `.duplicate-chip`) plus
      a new test asserting a thumbnail click both switches the stage and moves the `.active` class.
      `npm run build` and `npm test -- --run` (88/88) both clean. Not yet manually verified against
      a live duplicate cluster in the running app.

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
        `unlinkedPhotoId`. `TaskEntity.photoId` is generated up front at task creation
        (`UploadController.java`, `UUID.randomUUID()`) and reused as the final
        `PhotoEntity`'s id in `PhotoUploadService.processNewAsset`, so it's already
        correct and available even mid-poll, not just once `COMPLETED`. Updated
        `TaskService.getTask`'s mapping to include it (only call site).
      - Frontend: `pollTaskStatus` now stores `task.photoId` onto the matching queue item.
        `UploadPreviewModal` takes just a `unlinkedPhotoId` + `onClose`, fetches
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
        `COMPLETED` with a `unlinkedPhotoId`; clicking it renders the popup's `<img>` with the
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
        (`id`/`status`/`unlinkedPhotoId`) — consistent with what `GET /api/tasks/{id}` already returns.
        Updated `UploadManager.jsx` (frontend only, per explicit instruction not to touch the
        already-changed backend) to read `data.task.id`/`data.task.photoId` instead of the old
        `data.taskId`, and to seed `unlinkedPhotoId` on the item immediately from that first response
        rather than waiting for the first poll tick (previously up to 3s later for no reason,
        since the backend generates `unlinkedPhotoId` at task-creation time — see above). Updated the
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
          (via `findByRole`) as soon as `unlinkedPhotoId` arrived — before their own `pollTaskStatus`
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

- [x] **Immich import moved from admin-only/env-var config to per-user, self-service** (user
      request: "It should be actually at user level not admin level"). Also fixed a real bug the
      user found by actually running the previous admin version against their real server: the
      "days" table was silently showing months, not days.
      - **Why it showed months**: confirmed against the user's real Immich server that
        `/timeline/buckets?size=DAY` no longer works - current Immich versions only support
        `size=MONTH` (the `DAY` bucket size was removed server-side at some point; the official
        web client now buckets by month and derives day groupings client-side). Fixed by having
        `ImmichImportService.syncDayIndex` fetch every asset in each month bucket (via the
        already-used `/search/metadata`, paginated) and group them locally by each asset's own
        taken-date to build real per-day rows. Renamed `ImmichDayBucketDTO` →
        `ImmichTimeBucketDTO` and `ImmichClient.getDayBuckets` → `getMonthBuckets` accordingly.
        Heavier than a plain day-count call (must pull full asset metadata for a whole month just
        to count-by-day), but there's no day-count-only endpoint left to ask for. Per-day
        *import* itself was unaffected - it already used `/search/metadata` with day-boundary
        `takenAfter`/`takenBefore`, never the buckets endpoint.
      - **Per-user credentials, encrypted with the user's own password hash** (explicit user
        decision, not a real password-derived KDF): `UserEntity` gained `immichBaseUrl` (plain) +
        `immichApiKeyEncrypted`/`immichApiKeyIv` (AES-GCM). New
        `service/immich/ImmichCredentialCipher`: the AES key is `SHA-256(user.password)` - i.e.
        the bcrypt hash already sitting in that row, not the raw password (we only ever see the
        raw password for the instant it's submitted, and background per-asset import tasks run on
        worker threads with no HTTP session to hold onto it). This means any thread that can load
        the `UserEntity` row can decrypt, with no session-caching or re-auth-to-import plumbing -
        at the honest cost of being obfuscation-at-rest rather than a real secret (DB read access
        gets you the key and the ciphertext from the same row). `UserService#resetPassword` now
        re-encrypts these two columns whenever a password changes (decrypt with the old hash,
        re-encrypt with the new one) so a password reset never silently strands the stored key.
        New endpoints `GET/PUT/DELETE /api/immich/settings` (never returns the decrypted key back
        to the client, only `{baseUrl, connected}`).
      - **`ImmichClient` rewritten to be stateless per-call** instead of building one
        `RestClient` bound to a single global base-url/api-key at startup: every method now takes
        `UserService.ImmichCredentials` (baseUrl + decrypted apiKey) as a parameter, and builds an
        absolute per-request URI/header from it. Removed `IPhotoProperties.Immich` and
        `iphoto.immich.*`/`IMMICH_URL`/`IMMICH_API_KEY` from `application.yml` entirely (user's
        call: "i'll remove them. Don't need any more") - no more global fallback config.
      - **`ImmichImportDayEntity`/`ImmichImportDayRepository`** are now scoped per user: added a
        `user` FK (`@OnDelete(CASCADE)` so deleting a user cleans these up), unique constraint
        moved from `(day)` to `(day, user_id)`, lookup/list methods became
        `findByDayAndUser`/`findAllByUserOrderByDayDesc`. **Caveat**: since `ddl-auto: update`
        can't backfill a new NOT NULL FK on existing rows, the user's previously-synced
        (month-mislabeled) rows in a real `immich_import_days` table will likely need to be
        cleared manually (e.g. `TRUNCATE immich_import_days;`) before restarting against this
        version - flagged to the user, not yet confirmed done.
      - **Routing**: `ImmichImportController` moved from `/api/admin/immich/**`
        (`hasRole('ADMIN')`) to `/api/immich/**` (falls under `SecurityConfig`'s
        `anyRequest().authenticated()` - any logged-in user, no role check). Every method now
        resolves `getCurrentUser()` and threads it through instead of a hardcoded "admin" param.
        `AlbumEntity`/`ShareEntity`'s `externalSource`/`externalId` unique constraint was left
        global (not per-user) - two different users' Immich servers producing colliding real
        UUIDs was judged negligible risk, not worth the extra scoping.
      - **Frontend**: `/admin/immich` route/sidebar link (admin-only) → `/immich` (any
        authenticated user, alongside `/share-links`). `ImmichImportManagement.jsx` gained a
        connection-settings form (server URL + API key, "leave blank to keep current key" on the
        key field, separate explicit Disconnect action) above the existing sync/import UI, which
        now only renders once `settings.connected` is true. `useImmichImport.js` updated to the
        new `/api/immich/**` paths plus `fetchSettings`/`saveSettings`/`disconnect`. New i18n keys
        (`immichSettingsTitle`, `immichBaseUrlLabel`, `immichApiKeyLabel`, etc., en+fr).
      - Verified: whole backend `javac` compile clean (incl. `src/test`), `npm run build` clean,
        full Vitest suite 86/86 pass. **Not yet run against the live app or a live Immich
        server** - same sandbox limitation as before (no `mvn`/Docker/live Immich access here);
        the user should restart, re-sync days, and confirm real per-day counts now look right
        before importing.
- [x] **Bug fix**: `ImmichClient`'s `takenAfter`/`takenBefore` request params (sent to
      `/search/metadata` by both `getAssetsForDay` and `getAssetsForMonth`) were built via
      `LocalDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)`, which doesn't produce a
      proper ISO 8601 datetime for two reasons: (1) it carries no timezone/offset at all - a
      day/month boundary computed in local wall-clock time was being sent with no indication of
      *which* timezone that time is in, even though a `timezoneOffset` parameter was already being
      passed into both methods and silently going unused for this; (2) it drops the seconds field
      entirely when it's zero, e.g. `"2024-01-15T00:00"` instead of `"2024-01-15T00:00:00"` (every
      day/month boundary here starts at exactly midnight, so this fired on literally every call).
      Fixed by adding `ImmichClient.isoInstant(LocalDateTime, timezoneOffset)`: applies the offset
      via `ZoneOffset.of(timezoneOffset)` and formats via `Instant.toString()`, which always
      produces a complete `yyyy-MM-ddTHH:mm:ss[.SSS]Z` string (seconds always present, unlike
      `ISO_LOCAL_DATE_TIME`). Verified: `javac` compile clean.
- [x] **Bug fix**: `ImmichClient`'s `/search/metadata` request body's `page` field was typed
      `String` and started as `null` on the first page of every `searchMetadata` call - Immich
      rejects `page` unless it's a number (not null, not a string). Changed
      `SearchMetadataRequest.page` to `Integer`, starting the pagination loop at `1` instead of
      `null`; the response's own `nextPage` still comes back as a string (or null when done), so
      it's parsed back into an `Integer` (`parsePageNumber`, defensively logs+stops on anything
      non-numeric) before being sent as the next request's `page`. Verified: `javac` compile
      clean.
- [x] **Immich per-asset import redesigned into two chained tasks for real crash-resilience**
      (user request: single-task design wasn't resilient enough - specifically, moving the asset
      file into its final folder *before* the DB row was safely committed). Replaced
      `TaskType.PROCESS_IMMICH_ASSET` with `SYNC_FROM_IMMICH` (phase 1) → `UPLOAD_FROM_IMMICH`
      (phase 2, queued ~1 minute after phase 1 completes).
      - **Phase 1 (`ImmichImportService.processSyncFromImmichTask`)**: fetches the asset + its
        metadata from Immich, downloads the original into a new `iphoto.storage.immich-temp`
        directory (new `IPhotoProperties.Storage.immichTemp` / `application.yml` key) at a
        deterministic `{photoId}.{ext}` path - deliberately *not* the final `{year}/{month}`
        folder yet. Serializes everything phase 2 needs (staged path + all metadata, incl. raw
        EXIF JSON) into one new DTO, `model/immich/ImmichPendingUploadPayload`, and queues phase 2
        with it as the task payload. Never touches `photos`/`photo_metadata`.
      - **Phase 2 (`ImmichImportService.finalizeUploadFromImmich`)**: generates the thumbnail (same
        method a normal upload uses, run against the staged file), then commits the
        `PhotoEntity`/`PhotoMetadataEntity` rows via a new `persistPhotoAndMetadata` method, and
        only *after* that commit succeeds moves the staged file into its final folder. **Why this
        ordering matters**: the old single-phase design moved the file to its final path *first*,
        then saved the DB rows - so a DB failure after the move left an orphaned file sitting at
        the final path with no row pointing at it, and since that `Files.move` call used no
        `REPLACE_EXISTING`, every future retry of that exact asset hit `FileAlreadyExistsException`
        and failed identically forever. Flipping the order (commit first, move only after) means a
        DB failure never leaves a file at the final path at all - nothing to block a retry.
      - **Getting a genuine, independent commit boundary was the tricky part**:
        `TaskWorkerManager.processTask` wraps an *entire* task execution (whichever branch handles
        it, plus the final task-status save) in one `TransactionTemplate.executeWithoutResult` call
        - so a plain DB save inside `finalizeUploadFromImmich` would still only actually commit
        once `processTask`'s whole lambda returns, well *after* the file-move line would run,
        defeating the whole point. `persistPhotoAndMetadata` is instead its own transaction with
        `PROPAGATION_REQUIRES_NEW`, built via an *explicit* `new TransactionTemplate(...)` +
        `setPropagationBehavior(...)` rather than an `@Transactional` annotation - because it's
        called from within `ImmichImportService` itself (same class as
        `finalizeUploadFromImmich`), and Spring's proxy-based `@Transactional` does not intercept
        same-class ("self-invocation") calls, which would have silently made `REQUIRES_NEW` a
        no-op. Once `persistPhotoAndMetadata` returns normally, the rows are guaranteed durably
        committed (independent of whatever `processTask`'s outer transaction does afterward), so
        it's genuinely safe to move the file right after.
      - **Retry logic reworked accordingly** (`triggerDayImport`): "already fully imported" is no
        longer just `photoRepository.existsById` - it now also requires the row's `originalPath`
        file to actually exist on disk (`isFullyImported`), since a row can now be committed with
        its file still sitting in immich-temp if phase 2's move step itself fails right after the
        commit. A day retrigger in that state resumes via `finalizeUploadFromImmich`'s own re-entry
        guard (row already exists → skip re-insert, just finish the pending move). If only phase 2
        previously failed (phase 1's staged file is still there), retriggering re-queues phase 2
        alone by reusing the failed task's own payload verbatim - no need to re-hit Immich for a
        failure that was never Immich's fault; only a phase-1 failure re-queues a fresh phase 1.
      - **Caught the parallel-write angle too**: multiple assets' phase-2 tasks for the same space
        finish concurrently on different virtual threads and all contend on
        `SpaceService.lockSpace`'s per-space, non-blocking `tryLock` - one losing that race throws
        `SpaceLockingException`, which `TaskWorkerManager` previously had no special handling for
        (would've hit the generic `catch(Throwable)` and become a permanent, spurious `FAILED`).
        Added a catch for it right next to the existing `ServiceNotAvailableException` one - same
        treatment (reset to `PENDING`, `executeAt=null`, retried on the next 5-second poll in the
        same process) rather than a real failure, since the lock is normally only held briefly.
      - `parseDayRowId` (used by both `recordAssetTaskOutcome`/`recordAssetTaskFailure`) now reads
        the task payload as a generic `JsonNode` tree instead of a fixed `Map<String,String>` type,
        since phase 1's payload is a flat string map but phase 2's is now a typed
        `ImmichPendingUploadPayload` record - both still serialize to a top-level JSON object with
        a `"dayRowId"` key, so one read path covers both without branching on task type.
      - `ImmichClient.downloadOriginalToTemp` (nanoTime-suffixed, non-deterministic name) replaced
        with `downloadOriginalTo(creds, assetId, targetPath)` (caller-supplied deterministic path,
        `REPLACE_EXISTING` since a retry safely re-downloads over its own previous attempt).
      - Verified: whole backend `javac` compile clean (incl. `src/test`). **Not yet run against
        the live app or a live Immich server** - same sandbox limitation as every other Immich
        change this session; the user should restart and re-test a day import, including
        deliberately interrupting/retrying it, to confirm the resilience behavior actually holds.
      - **Follow-up (user request)**: extracted the ad-hoc `new TransactionTemplate(...) +
        setPropagationBehavior(REQUIRES_NEW)` construction out of `persistPhotoAndMetadata` into a
        new reusable `service/RequiresNewTransactionService` (`runInNewTransaction(Supplier<T>)` /
        `runInNewTransaction(Runnable)`) - same-class-self-invocation-safe REQUIRES_NEW for any
        future caller, not just this one. Also fixed a real bug caught during this cleanup: the
        original inline version reused the `user`/`space` entity instances loaded by
        `finalizeUploadFromImmich`'s *outer* (suspended) transaction inside the new one - since
        those instances belong to a different, suspended persistence context,
        `entityManager.refresh(space)` would have thrown (refresh requires a genuinely managed
        entity, not just a matching id). Fixed by having `persistPhotoAndMetadata` take plain
        `userId`/`spaceId` and re-fetch both fresh from inside its own new transaction. (The
        existing `recordAssetTaskOutcome`/`recordAssetTaskFailure` methods keep their plain
        `@Transactional(REQUIRES_NEW)` - those are always called cross-bean from
        `TaskWorkerManager`, so the self-invocation trap doesn't apply to them.)

- [x] **Feature**: Lightbox face-box hover refinement. Hovering the "Faces Detected" zone already
      showed every detected face's box on the image (`showFaces`, toggled by that zone's own
      `onMouseEnter`/`onMouseLeave`) — that part already worked. Now hovering one specific
      `.face-item` in that list narrows the display down to *only* that face's box: added a
      `hoveredFaceId` state, set/cleared by each `.face-item`'s own `onMouseEnter`/`onMouseLeave`
      (identity is `face.id ?? index`, matching the existing `key` fallback for faces without a
      linked person). The on-image box render condition became `showFaces && (hoveredFaceId ===
      null || (face.id ?? index) === hoveredFaceId)`, so leaving one face (but staying inside the
      zone) falls back to showing every box again. Also clear `hoveredFaceId` on the zone's own
      `onMouseLeave` so no stale single-face state can survive past the zone boundary. Frontend
      only — no backend/API change, all the data was already in `faces`.
      - Verified: `npm run build` clean, full `npm test -- --run` 86/86 pass.

- [x] **Fix** (user request): `ImmichImportService#resolveDateTaken` now tries a real fallback
      before ever stamping `LocalDateTime.now()`. Previously, once none of
      `exifInfo.dateTimeOriginal`/`localDateTime`/`fileCreatedAt` parsed (e.g. an asset with
      stripped EXIF and no local-timezone timestamp from Immich), it went straight to "now" -
      silently wrong for older imported assets. Split into `resolveDateTakenFromDetail` (the
      existing DTO-only attempts, returns `null` instead of "now" on total failure) plus two
      overloads: the day-index-sync one (`syncDayIndex`, only has search-metadata DTOs for
      potentially thousands of assets, never downloads them) still falls back straight to "now" -
      no file exists yet to inspect, and downloading every asset just to group by day would be
      far too expensive. The per-asset one (`processSyncFromImmichTask`, called right after
      `immichClient.downloadOriginalTo`) falls back to the newly-generalized
      `PhotoUploadService#extractBestCreationDate(File, String fileName)` against the just-staged
      file - same EXIF/video-metadata/filename/filesystem guesswork a normal upload already
      relies on - before finally giving up and using "now". `extractBestCreationDate` used to
      require a full `PhotoEntity` (only ever used for `photo.getFileName()`); added a
      `String fileName` overload so `ImmichImportService` doesn't need a `PhotoEntity` built yet
      to use it, with the original `PhotoEntity` overload now just delegating to it.
      - Verified: whole backend `javac` compile clean (main + test sources). Not yet run against
        the live app or a live Immich server, same sandbox limitation as the rest of the Immich
        work.

- [x] **Observability** (user request): added `log.info` start/end lines on the two Immich import
      task types (`ImmichImportService#processSyncFromImmichTask` /
      `#finalizeUploadFromImmich`, dispatched by `TaskWorkerManager` for
      `SYNC_FROM_IMMICH`/`UPLOAD_FROM_IMMICH`), each line naming the task id and photo/asset id so
      a run can be traced through the log. `processSyncFromImmichTask` logs its start, then one of
      three ends: already-imported skip, content-duplicate skip, or staged-and-queued-phase-2.
      `finalizeUploadFromImmich` logs its start, then one of two ends: resumed-an-existing-row, or
      committed-and-moved-to-final-path. Deliberately scoped to just these two - they're the only
      methods actually backing a `TaskType` (i.e. literally "tasks" in this codebase); the
      synchronous, non-task trigger methods (`syncDayIndex`, `triggerDayImport`,
      `ImmichAlbumImportService#triggerAlbumImport`) were left alone.
      - Verified: whole backend `javac` compile clean (main sources).

- [x] **Fix** (user request): Immich shared-link import now keeps the same share link where
      possible. `ImmichAlbumImportService#processOneSharedLink` previously always generated a
      fresh iphoto share name (`sanitizeShareName(albumName + "-" + linkId)`) - a different name
      than whatever Immich itself exposed, so the actual URL changed on migration. Now
      `resolveShareName` reuses Immich's own shared-link `key` (`ImmichSharedLinkDTO.key`, the
      literal token from `immich-host/share/{key}`) verbatim as iphoto's share `name` - the
      `/share/{userMd5}/{name}` path segment - case preserved (no lowercasing/slugifying, which
      would change the token). The `/share/{userMd5}/...` prefix itself can't be dropped - every
      iphoto share is addressed that way, Immich-imported or not - so this is the closest iphoto
      can get to true URL parity: whatever downstream ordering/reverse-proxy history exists around
      the old key stays meaningful. Falls back to the old generated-slug behavior only if Immich
      sent no key, or the key happens to collide with a share name this user already has (checked
      via `ShareRepository#findByUserMd5AndName` before save, not just trusted - a
      unique-constraint violation would abort that share's whole per-item transaction).
      - Verified: whole backend `javac` compile clean (main sources). Not yet checked against a
        live Immich server's real `key` format/length.

- [x] **Feature** (user request): allow reimporting a `COMPLETED` Immich day.
      `ImmichImportService#triggerDayImport` was already idempotent/re-runnable by design (its own
      comment already said so) - it re-fetches the day's asset list from Immich fresh on every
      call and only queues what `isFullyImported` says isn't already there, correctly picking up
      assets added on Immich *after* the last import and refreshing `immichAssetCount`. The only
      thing actually stopping a reimport was the frontend: `ImmichImportManagement.jsx`'s
      `IMPORTABLE_STATUSES` didn't include `COMPLETED`, so the button was disabled once a day
      finished. Added `COMPLETED` to that list (still excluding `IN_PROGRESS` - re-triggering a run
      that's already mid-flight would just be confusing) and a distinct "Reimport" label/i18n key
      (`immichReimportButton`, en/fr) for that case, separate from "Retry" (`PARTIAL`/`FAILED`,
      implies a failure) and "Import" (`NOT_IMPORTED`, first run).
      - Verified: `npm run build` clean. No backend change was needed.

- [x] **Fix/reliability** (user request): isolated heavy task processing (face detection,
  duplication) onto their own strictly-bounded pool. `TaskWorkerManager` previously ran every
  task type - `PROCESS_UPLOAD`, `PERMANENT_DELETE`, the Immich import tasks, and also
      `HASH_IMAGE`/`CHECK_DUPLICATION`/`ISOLATE_FACES`/`FACE_RECOGNITION` - through the same
      `Executors.newVirtualThreadPerTaskExecutor()`, with no cap on how many could run at once.
  That's fine for I/O-bound work (uploads, DB writes, Immich HTTP calls - virtual threads park
  cheaply while waiting), but the four CPU-bound post-upload pipeline stages (image
  hashing/ORB-descriptor extraction and duplicate matching in `DuplicationService`; face
  detection/recognition in `FaceDetectionService`/`FaceRecognitionService`) are native/CPU-heavy
  and don't yield the way virtual threads expect - they pin their carrier thread rather than
  parking - so a burst of them (e.g. right after importing a big Immich day) could monopolize
  every carrier thread and starve unrelated tasks (new uploads, Immich sync) of progress.
  - Added a new `Set<TaskType> HEAVY_TASK_TYPES` (`HASH_IMAGE`, `CHECK_DUPLICATION`,
        `ISOLATE_FACES`, `FACE_RECOGNITION`) and a second, isolated `ExecutorService` -
        `Executors.newFixedThreadPool(3, ...)`, named platform threads (`heavy-task-N`) rather than
  virtual ones, since the whole point is a hard cap on real parallelism, which virtual
  threads don't reliably give for non-yielding work. `pollLightPendingTasks` now routes each task
  to `heavyTaskExecutor` or the existing `executorService` based on its type - at most 3 heavy
  tasks run at once, however many are queued; everything else is unaffected.
  - `pollLightPendingTasks` had to start carrying task type, not just id, to make that routing
  decision - added a small local `TaskRef(UUID id, TaskType type)` record.
  - **Found and fixed a real pre-existing bug while touching this method**: the old dispatch
  (`executorService.submit(() -> { Thread t = Thread.ofVirtual()...start(...); t.start(); })`)
        called `Thread.start()` *twice* on the same virtual thread - `.start(runnable)` already
  starts it, then the code called `.start()` again, which throws `IllegalThreadStateException`
        every time (silently swallowed since the `Future` from the outer `submit()` was never
  checked). The task still ran fine because the first `start()` had already kicked it off
  independently - but the outer virtual thread was pure waste, spawned only to immediately
  throw. Simplified both dispatch paths to a plain `executor.submit(() -> processTask(id))`,
  which is already correct.
  - `@PreDestroy shutdown()` now shuts down both executors.
  - **Follow-up (user request)**: that simplification had dropped the per-task thread naming
  (`"backend-task" + taskId`) the old buggy dispatch was trying to give each light task -
  useful for identifying which task a given virtual thread is running from a thread dump.
  Reintroduced it, still without the double-`start()` bug: light tasks (everything not in
        `HEAVY_TASK_TYPES`) now get `Thread.ofVirtual().name("backend-task" + ref.id()).start(...)`
        directly - one named virtual thread per task, `start()` called exactly once - rather than
  going through a shared `Executors.newVirtualThreadPerTaskExecutor()` (removed, since a
        `ThreadFactory`-based executor can only name threads with an auto-incrementing counter, not
  the actual per-task id needed here; heavy tasks still go through `heavyTaskExecutor.submit`
        since that pool's own `heavy-task-N` naming is enough and it needs the bounded queue).
        `shutdown()` updated accordingly - only `heavyTaskExecutor` is an actual `ExecutorService`
        now.
  - Verified: whole backend `javac` compile clean (main + test sources).

- [x] **Feature** (user request): bulk-import up to 30 Immich days at once, plus a button to hide
      already-completed days from the table.
      - **Backend**: `ImmichImportService.triggerDayImport` was split into a thin
        `@Transactional` public entry point and a private `triggerDayImportInternal` carrying the
        actual logic, so the new `triggerBulkDayImport(List<LocalDate> days, user, tz)` could call
        it per-day without hitting the classic Spring AOP self-invocation trap (calling `this
        .triggerDayImport(...)` from within the same class would silently skip the
        `@Transactional` proxy entirely). Each day instead runs in its own independently-committed
        transaction via the existing `RequiresNewTransactionService`, and is individually
        try/caught so one broken day can't affect any other - same per-item-transaction pattern
        `ImmichAlbumImportService` already uses, for the same reason. Capped at
        `MAX_BULK_IMPORT_DAYS = 30`, throwing `IllegalArgumentException` above that (a day can
        itself queue hundreds of per-asset tasks, and Immich calls are already throttled via
        `ImmichClient`'s own `Semaphore(8)` - a much bigger batch would just queue an enormous
        number of tasks in one request for no real benefit over clicking "Import Selected" a few
        times). New `POST /api/immich/days/import-bulk` (`ImmichBulkImportRequest{days}` →
        `List<ImmichImportDayDTO>`).
      - **Frontend**: `ImmichImportManagement.jsx` gained a checkbox column (disabled for
        non-importable/`IN_PROGRESS` days, and once 30 are already selected), a header
        "select all importable" checkbox (visible-rows-aware, still capped at 30), and an
        "Import Selected (n/30)" button calling the hook's new `startBulkImport` (which polls each
        triggered day individually, same as `startImport` already does for a single day). Also
        added a "Hide completed days" toggle button (`hideCompleted` local state, filters `days`
        client-side before rendering - no backend change) with a message shown when every day ends
        up hidden.
      - Verified: whole backend `javac` compile clean (main + test sources); `npm run build`
        clean; full `npm test -- --run` 86/86 pass. Not yet run against a live Immich server.

- [x] **Fix** (user request): unstick a day permanently stuck at `IN_PROGRESS`, plus a
  show/hide toggle for in-progress days.
  - **Root cause**: `TaskWorkerManager.pollPendingTasks` only ever picks up `PENDING` tasks
  (`taskRepository.findExecutableTasks(TaskStatus.PENDING.name(), ...)`). If the app
  restarts (or a virtual thread otherwise dies) while a `SYNC_FROM_IMMICH`/
        `UPLOAD_FROM_IMMICH` task is `PROCESSING`, nothing ever picks it back up - the task, and
  the day it belongs to, is orphaned at `IN_PROGRESS` forever. `triggerDayImportInternal`'s
  per-asset loop only ever reclaimed a task that was `FAILED`, so re-triggering that day
  (even once the frontend allowed it) wouldn't actually do anything for an orphaned one.
  - **Fix**: new `ImmichImportService#isReclaimable(TaskEntity)` - true for a `FAILED` task
  (unchanged) or a `PROCESSING` one whose `updatedAt` is older than `STUCK_TASK_THRESHOLD`
        (15 minutes - comfortably longer than even a slow face-detection run or large video
  download, chosen since `TaskEntity`'s `@PreUpdate` bumps `updatedAt` right when
        `pollLightPendingTasks` first flips a task to `PROCESSING`, so it doubly serves as "when
  processing started"). The per-asset loop now reclaims (deletes + requeues) a stale
        `UPLOAD_FROM_IMMICH` the same way it already did for a failed one (reuse the staged file,
  no need to re-hit Immich), and for anything else reclaimable (stale/failed
        `SYNC_FROM_IMMICH`) deletes it and restarts that asset from step 1 - safe, since
        `SYNC_FROM_IMMICH` re-downloads deterministically to the same `immich-temp` path and
        `finalizeUploadFromImmich` is already idempotent/resumable.
  - **Frontend**: `IMPORTABLE_STATUSES` now includes `IN_PROGRESS` (button label falls back to
        "Retry", same as `PARTIAL`/`FAILED`) - retriggering a *genuinely* still-running day is a
  harmless no-op server-side (nothing reclaimable yet), so there's no real downside to always
  allowing the click. Also added a "Hide in-progress days"/"Show in-progress days" toggle
  button (`hideInProgress` local state), mirroring the existing "Hide completed days" one -
  both filters now compose together on `visibleDays`.
  - Verified: whole backend `javac` compile clean (main + test sources); `npm run build`
        clean; full `npm test -- --run` 86/86 pass. Not yet exercised against the user's actual
  stuck day - should confirm clicking "Retry" on it actually reclaims the orphaned task and
  the day eventually reaches a terminal status.

- [x] **Reliability** (user request): a database error or a network error (talking to Immich)
      mid-task no longer permanently fails the task - it's retried 5 minutes later instead.
      `TaskWorkerManager.processTask`'s catch chain previously only special-cased
      `ServiceNotAvailableException`/`SpaceLockingException` (both: `PENDING` +
      `executeAt=null`, immediate same-process retry on the next 5s poll) before falling through
      to the generic `catch (Throwable e)` → `FAILED`. Added two more catches, both before the
      generic one, both `PENDING` + `executeAt = now + 5 minutes` (a real delay this time, not
      immediate - a DB/network problem is more likely to need actual recovery time than momentary
      lock contention):
      - `DataAccessException` (Spring's own translation target for essentially every low-level
        persistence failure - JDBC `SQLException`, connection pool errors, deadlocks, query
        timeouts - so this one catch covers the DB-error case broadly, no need to chase individual
        driver exception types).
      - `ImmichApiException` (the catch-all every `ImmichClient` method already wraps REST/network
        failures in, including plain connectivity/timeout issues).
      - Neither path calls `ImmichImportService#recordAssetTaskFailure` (same as the two existing
        retry catches) - a delayed retry isn't a real failure for the Immich day counters.
      - **Documented but not solved caveat** (inherent to the problem, not a gap in this fix): if
        the database is down badly enough that even the recovery write
        (`taskRepository.save(task)`, right after the catch block, inside the same transaction)
        can't complete either, the task is left exactly as it was (`PROCESSING`) - persisting
        "retry later" itself requires the database to be reachable. Only Immich's
        `SYNC_FROM_IMMICH`/`UPLOAD_FROM_IMMICH` tasks currently have any reclaim path for that
        orphaned state (`ImmichImportService#isReclaimable`, and only once a user re-triggers that
        day) - other task types (`HASH_IMAGE`, `ISOLATE_FACES`, etc.) have none yet. A general
        orphaned-`PROCESSING`-task sweeper (e.g. on startup, mirroring `isReclaimable`'s staleness
        idea) would close that gap but is a bigger change, out of scope here.
      - Verified: whole backend `javac` compile clean (main + test sources). Not exercised against
        a real DB outage or network blip - hard to simulate in this sandbox.

- [x] **UX** (user request): replaced the "Hide completed days"/"Hide in-progress days" toggle
      buttons with a single status-filter dropdown on the Immich import page. User's framing: a
      dropdown with "COMPLETED, IN PROGRESS, PENDING" (their word for a day that hasn't started
      importing yet - `ImmichImportDayStatus.NOT_IMPORTED`). Implemented as one `<select>`
      (`statusFilter` state, default `'ALL'`) with every real day status as an option -
      `NOT_IMPORTED`/`IN_PROGRESS`/`PARTIAL`/`FAILED`/`COMPLETED` - not just the three named, so
      `PARTIAL`/`FAILED` days remain reachable through the same mechanism rather than only through
      "All". `visibleDays` is now a single `statusFilter === 'ALL' ? days :
      days.filter(...)` instead of two independently-composing boolean toggles. Relabelled
      `NOT_IMPORTED`'s displayed text from "Not imported" to "Pending" (`immichDayStatusNotImported`
      i18n string, en/fr) per the user's own wording, matching what shows both in the dropdown and
      the status badge column - the enum name itself is unchanged, this is a display-string-only
      change. Removed the now-unused hide/show button i18n keys and the old "all days hidden"
      message key (replaced with a filter-agnostic `immichNoDaysMatchFilterMessage`).
      - Verified: `npm run build` clean, full `npm test -- --run` 86/86 pass. No backend change.

- [x] **Bug fix, found via live investigation** (user report: "on day 2023-12-24 second import
      didn't import all photos, first try failed with many database access exception"). Root-caused
      against the actual running app + live DB (`docker exec ... psql`) + disk, not guessed:
      - **Every one of the 140 assets for 2023-12-24 was, in reality, already fully imported** -
        140/140 `photos` rows committed, every `original_path` pointing at a real file on disk.
        Yet the day sat at `IN_PROGRESS`, `imported_count` stuck at 133 (later 133, originally 121
        before the user's "second import" click). The gap: 7 `UPLOAD_FROM_IMMICH` tasks were
        orphaned in `PROCESSING` - their real work (DB commit + file move) had already fully
        succeeded, but the task never reached the `task.setStatus(COMPLETED)` +
        `recordAssetTaskOutcome` lines a couple of statements later in
        `TaskWorkerManager.processTask`, most likely killed by an app restart at exactly that
        instant. `triggerDayImportInternal`'s `isFullyImported` check correctly recognized these 7
        as done and skipped re-touching them (so no risk of re-download/duplication) - but nothing
        credited the day's counters for them either, so the day could never reach a terminal
        status even though it was, in truth, 100% complete.
      - The "first try failed with many database access exception" part: `hs_err_pid9398.log`
        (this machine) shows a real JVM-level `SIGSEGV` inside `libtorch_cpu.so`
        (`dnnl_primitive_execute`) during face-detection inference
        (`FaceDetectionService.detectAndSaveFaces` → DJL's PyTorch engine), on a `heavy-task-0`
        thread. A native crash like that kills the whole process instantly, severing every open DB
        connection mid-flight - explaining a burst of `DataAccessException`s across whatever tasks
        happened to be writing at that exact moment (caught by this session's earlier
        `DataAccessException`-retry fix, which is why they became delayed retries rather than
        permanent `FAILED`s). **This crash is not new or caused by anything from this session** -
        the identical crash signature also appears in `hs_err_pid11623.log` (Aug 29) and
        `hs_err_pid24151.log` (Aug 17), so it's a pre-existing, recurring native-library
        instability worth its own separate investigation (likely a DJL/PyTorch native
        thread-safety issue under concurrent inference) - flagged here, not fixed, since it's a
        different problem from the counter/status bug this entry actually fixes.
      - **Fix**: new `ImmichImportService#reconcileOrphanedCompletedAsset(photoId, dayRowId)`,
        called from the `isFullyImported` branch of the per-asset loop instead of a bare
        `continue`. Finds any non-`COMPLETED` `SYNC_FROM_IMMICH`/`UPLOAD_FROM_IMMICH` task left
        over for an asset that's already genuinely done, deletes it, and credits the day's
        imported counter exactly once (`immichImportDayRepository.incrementImported` +
        `finalizeDayStatusIfDone` - the same atomic-increment path `recordAssetTaskOutcome`
        already uses). Idempotent by construction: once the lingering task is cleared, a later
        call for the same asset finds nothing left and is a no-op, so this can never double-count.
      - Verified: whole backend `javac` compile clean (main + test sources). Not yet re-run
        against the live app - restarting it (a real restart, not just an IDE hot-swap - see below)
        and clicking "Retry" on 2023-12-24 again should now bring it to `COMPLETED`.
      - **Separate observation, not addressed here**: the currently-running app instance is
        launched under an IntelliJ debug session (`-agentlib:jdwp=...`). IntelliJ's HotSwap can
        only update method *bodies* on already-loaded classes, not add new methods/fields - so
        several of this session's earlier structural changes to `ImmichImportService`
        (`isReclaimable`, the `reconcileOrphanedCompletedAsset` above, etc.) may silently not have
        taken effect in the live process even though `target/classes` on disk has the correct
        compiled bytecode (confirmed via `javap`). A genuine stop-and-restart of the run
        configuration (not relying on live HotSwap) is needed for this fix - and every other
        `TaskWorkerManager`/`ImmichImportService` structural change from earlier this session - to
        actually take effect.

- [x] **Reliability** (user request): general hourly sweep to reclaim tasks orphaned in
      `PROCESSING`, across every task type - closes the gap flagged in the two entries just above
  (both scoped to Immich specifically: `isReclaimable`'s per-day-retry reclaim, and the
  DataAccessException/ImmichApiException retry-with-delay catch). `pollLightPendingTasks` only ever
  looks at `PENDING` tasks, so a task left `PROCESSING` when its worker thread dies mid-task
  (app restart, JVM crash - a real instance of exactly this was found and root-caused in this
  session's live investigation) is otherwise never picked up again, for any task type, unless a
  human happens to notice and manually retry through a feature-specific button.
  - New `TaskRepository#findByStatusAndUpdatedAtBefore(status, before)` - `updatedAt` is used
  as "when processing started", since `TaskEntity`'s own `@PreUpdate` bumps it the moment
        `pollLightPendingTasks` first flips a task to `PROCESSING` (same reasoning `isReclaimable`
        already relies on).
  - New `TaskWorkerManager#reclaimStuckProcessingTasks`, `@Scheduled(fixedDelay = 1 hour)`:
        finds every task `PROCESSING` for over `STUCK_TASK_AGE` (1 hour) and resets it to
        `PENDING` with `executeAt` set to a **randomized** 1-15 minute delay
  (`MIN_RETRY_DELAY_MINUTES`/`MAX_RETRY_DELAY_MINUTES`, `ThreadLocalRandom`) rather than one
  fixed offset - spreads reclaimed tasks out so a sweep that reclaims many at once doesn't
  make them all executable at the exact same instant and recreate the very pile-up that
  likely orphaned some of them in the first place.
  - Deliberately independent of the existing Immich-specific `isReclaimable` mechanism, not a
  replacement for it - that one still runs on-demand (import-day click) and knows how to
  reuse a stuck `UPLOAD_FROM_IMMICH`'s payload without re-hitting Immich; this one is a
  blunter, fully-automatic, all-task-types safety net that needs no user action at all.
  Reprocessing after being swept is safe for every task type verified: `PROCESS_UPLOAD`
        (hash-dedup on the temp file), `SYNC_FROM_IMMICH`/`UPLOAD_FROM_IMMICH` (idempotent/resumable
  by design), `HASH_IMAGE`/`CHECK_DUPLICATION`/`ISOLATE_FACES`/`FACE_RECOGNITION` (recompute
  in place, harmless to redo) - `PERMANENT_DELETE`'s idempotency on a second run wasn't
  independently verified here (not touched by this change either way; worst case if it
  somehow isn't is a task ending FAILED instead of stuck forever, still strictly better than
  today).
  - Verified: whole backend `javac` compile clean (main + test sources). Not yet observed
  firing against a real stuck task in the live app.

- [x] **Bug fix** (user report: "import of immich on 2026-08-01 result is more imported photo
      count than expected, meanwhile number of photo imported is good, only count in immich
      import tool is bad"). Root-caused by analysis (no live DB access this time) - the actual
      `photos` rows/files are correct, only the day's tracked `imported_count` was inflated. This
      is the double-counting hole in `reconcileOrphanedCompletedAsset` (added two entries above,
      for the 2023-12-24 investigation) that its own "Idempotent by construction... can never
      double-count" claim missed:
      - **The real race**: `TaskWorkerManager.processTask` does `task.setStatus(COMPLETED)` then
        `immichImportService.recordAssetTaskOutcome(task, outcome)` then
        `taskRepository.save(task)`, all inside one `transactionTemplate` block - but
        `recordAssetTaskOutcome`/`recordAssetTaskFailure` were `@Transactional(propagation =
        REQUIRES_NEW)`, which suspends that block and commits the counter increment
        independently, immediately - well before the task's own `COMPLETED` status commits a
        couple of statements later. A crash/restart landing in that exact window (the same class
        of interruption the 2023-12-24 investigation already found this app susceptible to -
        JVM-level native crashes, forced restarts) leaves the counter already incremented but the
        task still showing its old, non-`COMPLETED` status. `reconcileOrphanedCompletedAsset` has
        no way to tell that apart from "outcome truly never recorded" - it finds the same task,
        deletes it, and increments the counter a SECOND time for one single real import.
      - **Fix**: `recordAssetTaskOutcome`/`recordAssetTaskFailure` changed from
        `@Transactional(propagation = REQUIRES_NEW)` to plain `@Transactional` (joins the ambient
        transaction from `TaskWorkerManager`'s `transactionTemplate` block instead of committing
        independently) - the counter update and the task's terminal status now commit atomically
        together, closing the crash window entirely. Nothing else needed `REQUIRES_NEW` here (only
        caller is `TaskWorkerManager.processTask`); the atomic `UPDATE ... SET x = x+1` queries
        these call into stay safe under concurrent tasks regardless of transaction propagation,
        and the counter's own cache staleness after those bulk updates is already handled by
        `@Modifying(clearAutomatically = true)` - so `finalizeDayStatusIfDone`'s subsequent
        `findById` still sees fresh values either way. `reconcileOrphanedCompletedAsset` itself
        left untouched - it stays correct for the case it was actually built for (outcome truly
        never recorded) once this race is closed.
      - Verified: whole backend `javac` compile clean. Not yet re-run against the live app -
        needs a real restart (not IntelliJ HotSwap, per the caveat two entries above) to take
        effect, and there's no way to retroactively correct `imported_count` values already
        inflated by this bug before today (2026-08-01's day row, and any other day that hit the
        same crash-timing window, will still show its already-doubled count until manually
        corrected or that day is re-triggered from scratch).

- [x] **Investigated, no code change needed** (user report: asset not added to a specific album
      even after triggering "Import Album and Share Link"; more broadly, no photos added to any
      created album). Root-caused against the live DB, not guessed - both symptoms trace back to
      the same cause, working as designed, not a bug:
      - **The album id the user searched by was the album's Immich id, not its iphoto id.**
        Albums don't keep the Immich UUID as their own primary key the way photos do -
        `PhotoEntity.id` has no `@GeneratedValue` (set directly to the Immich asset UUID, per the
        original migration design), but `AlbumEntity.id` does have `@GeneratedValue` and can't be
        overridden the same way - the Immich album id is preserved separately in `external_id`
        instead. Confirmed via `select * from albums where external_id = '<the id searched for>'`
        - found immediately under a different `id`.
      - **Why no photos were linked**: `ImmichAlbumImportService.processOneAlbum` only links a
        member asset that already passes `photoRepository.existsById` *at the moment the album
        import runs* (`AlbumService.addPhotosToAlbum` requires the photo already committed and in
        a MAIN space) - anything not yet locally imported is counted as skipped, not queued for
        later. Timestamps proved this precisely for the reported case: the album ("Noël 2023",
        and 87 of the user's other 88 albums) was processed by "Import Albums & Shares" at
        22:45:09, but the specific asset in question wasn't actually committed to `photos` until
        23:26:55 - 41 minutes *later*, as day 2023-12-24's import finished during this same
        session. At 22:45:09 almost no days were fully imported yet, so nearly every album's
        `linkable` list came back empty - the album shells still got created correctly (all 88
        rows have the right `external_id`), just with 0 photos each. Only one album (13 photos)
        happened to have every member already imported by then.
      - **Not data loss, fully recoverable**: `triggerAlbumImport` is idempotent (find-or-create
        by `external_id`) and re-checks membership fresh on every call - simply re-clicking
        "Import Albums & Shares" now that more days are imported picks up everything that's since
        become available, no code fix required.
      - **Decision point raised, user chose not to change it for now**: offered to make a
        newly-imported album's `id` equal the Immich album UUID (mirroring photos, by dropping
        `@GeneratedValue` on `AlbumEntity.id` for the Immich-import path) instead of the current
        generated-id-plus-`external_id` scheme. User picked "re-click Import Albums for now" over
        changing the id scheme - revisit only if the current scheme actually causes a real problem
        later, not preemptively.

- [x] **Reliability** (user request): capped light-task virtual thread concurrency at 40 - closes
  a real DB-connection-exhaustion risk that the reintroduced per-task named virtual threads
  (previous entry) had no protection against. `pollLightPendingTasks`'s light-task path (everything
  not in `HEAVY_TASK_TYPES`: `PROCESS_UPLOAD`, `PERMANENT_DELETE`, `SYNC_FROM_IMMICH`,
      `UPLOAD_FROM_IMMICH`) spawned one fresh, unbounded virtual thread per executable task -
  each one holds a DB connection/transaction for its duration (`processTask`'s
      `transactionTemplate`), so a burst of simultaneously-executable tasks (a big Immich day
  queuing hundreds of `SYNC_FROM_IMMICH` tasks at once, say) could each grab a connection at
  the same instant against `application.yml`'s finite `hikari.maximum-pool-size` (100) - the
  same kind of exhaustion this session's live investigation of day 2023-12-24 saw play out
  (there triggered by a JVM crash instead, but the underlying risk is identical).
  - New `Semaphore lightTaskConcurrency` (`MAX_CONCURRENT_LIGHT_TASKS = 40`, comfortably under
  the pool's max, leaving headroom for `heavyTaskExecutor` (3) and everything else - web
  requests included - sharing the same pool). `pollLightPendingTasks` now calls
        `lightTaskConcurrency.acquireUninterruptibly()` before starting each light task's named
  virtual thread, `release()`d in a `finally` around `processTask`.
  - Deliberately blocks the *scheduled-poller invocation itself* when at capacity, not any
  individual task - same idea as a bounded thread pool's `submit()` blocking when full. Safe
  here specifically because `@Scheduled(fixedDelay = ...)` only starts the next tick after
  the current one returns, so a burst just delays the next 5-second poll rather than piling
  up concurrent `pollLightPendingTasks` invocations.
  - Heavy tasks are unaffected (already capped at 3 via their own separate, non-virtual pool);
  worst-case total concurrent DB-connection users across both paths is ~43, still well under
  the pool's 100 max.
  - Verified: whole backend `javac` compile clean (main + test sources). Not yet observed
  under a real high-concurrency burst in the live app.

- [x] **Follow-up** (user request): heavy tasks now hold back while light-task load is at
  capacity, rather than being dispatched on top of it. `pollLightPendingTasks` now checks
      `lightTaskConcurrency.availablePermits() == 0` before submitting a heavy task to
      `heavyTaskExecutor` - since the semaphore can never actually hold more than
      `MAX_CONCURRENT_LIGHT_TASKS` (40) permits out, `== 0` is exactly "light-task demand is
  at/over capacity". Heavy tasks hold a DB connection for their duration too, so piling them on
  top of an already-saturated light-task load would add further pressure on the same finite
  connection pool for no benefit. When held back, the task is reverted to `PENDING`
      (`executeAt` cleared, undoing the `PROCESSING` flip from earlier in the same
      `pollLightPendingTasks` call) via a new `holdBackTask` helper - same "immediate retry on the very
  next 5-second poll" shape `processTask`'s `ServiceNotAvailableException`/
      `SpaceLockingException` catches already use, just invoked before the task ever started
  running rather than after a failure. Deliberately doesn't wait for the much coarser 1-hour
  stuck-task sweep to notice - it'll just get reconsidered on the next poll once light-task
  load eases.
  - Verified: whole backend `javac` compile clean (main + test sources). Not yet observed
  under a real high-concurrency burst in the live app.

## Open / Not Yet Done

- [x] **Bugfix** (user report): moving a photo to garbage from `DuplicateClusterModal` (the "×N
      similar" popup in `PhotoGrid.jsx`) closed the modal and lost the Library grid's scroll
      position. Root cause: `handleMoveToGarbage` (`usePhotoActions.js`) went through the shared
      `refreshAndMaintainScroll` (`App.jsx`), which does `fetchPhotos(0, true, ...)` — a full
      reset to page 0 only, silently dropping every later page the user had scrolled into, then
      trying to restore the old `scrollTop` pixel value against much shorter content. Since
      `DuplicateClusterModal`'s visible cluster is deliberately re-derived live from `photos`
      (so removing a member updates it in place, see `PhotoGrid.jsx`'s `openDuplicateItem`
      comment), a page-0 reset could drop the open cluster's primary out of the list entirely,
      auto-closing the modal even though the cluster itself still had other members left.
      - **Fix**: `handleMoveToGarbage` no longer calls `refreshAndMaintainScroll`. It now calls a
        `removePhotosLocally(ids)` callback (`fetchAlbums`/`fetchSpaces`, for the count badges,
        are still called too) - `removePhotosLocally` and the two are threaded into
        `usePhotoActions` as new trailing params. `removePhotosLocally` is defined in `App.jsx`
        (see the day-index entry below this one - after that landed, "update the photo list in
        place" had to handle two different state shapes depending on the current view, not just
        one flat array, hence a callback rather than a raw `setPhotos` passed straight through).
        The actual removal/scrubbing logic lives in `utils/photoListUpdates.js`
        (`removeFromFlatPhotos`/`removeFromPhotosByDay`) - both filter the garbage'd ids out and
        scrub them from every *remaining* photo's own `duplicates` sub-array (that's what
        `PhotoGrid.jsx`'s cluster grouping actually reads members from — not a re-derivation off
        the top-level list), so a cluster's members stay correct even after its displayed
        "primary" (whichever photo happens to be first-in-array/first-loaded) gets garbage'd out
        from under it.
      - **Deliberately not touched**: `handleRestore`/`handleMoveToMain`/`handlePermanentDelete`
        still go through the old `refreshAndMaintainScroll` full-reset path, and have the same
        latent scroll/page-0 issue if triggered from a scrolled-down view - just not what was
        reported. Worth the same treatment later if it comes up.
      - **Tests**: `hooks/usePhotoActions.test.js` updated — asserts `refreshAndMaintainScroll` is
        NOT called from `handleMoveToGarbage` anymore and that `removePhotosLocally` is called
        with the right ids; `utils/photoListUpdates.test.js` covers the removal/scrubbing logic
        itself directly.

- [x] **Refactor** (user request): fetch photos day-by-day instead of arbitrary fixed-size pages,
      for Library/Album/Space (Garbage/Person deliberately kept on the old flat pagination -
      never part of this request). Fully replaces the old month-timeline/cursor machinery
      (`jumpToMonth`/`beforeDate`/`afterDate`, `TimelineMonthDTO`, `LibraryTimeline.jsx`,
      `useMonthlyTimeline.js`) - all deleted, not left alongside the new system.

      Settled design, differs from the original sketch below in two ways worth flagging for
      anyone re-reading this entry: (a) a derived `groupedDay` column on `PhotoEntity` instead of
      a separate `DayEntity` table - avoids the write-time sync burden entirely (question #1
      below), since it's just kept current via JPA lifecycle callbacks on the photo row itself;
      (b) per-day photos ARE paginated (size 100), not a flat unpaginated response (question #2).
      Questions #3 and #4 resolved as stated in the original sketch (replaces the month-timeline;
      Album detail groups through `album_photos` instead of `space_id`).

      - **Backend**: `PhotoEntity.groupedDay` (`LocalDate`, `@Column(name = "grouped_day")`),
        synced via `onCreate`/`onUpdate` (`@PrePersist`/`@PreUpdate`) from `dateTaken` - covers
        every existing `dateTaken`-setting path (`PhotoUploadService`, `ImmichImportService`,
        `PhotoService.updatePhotoDate`) with zero extra code at any call site. Composite index
        `idx_photos_space_grouped_day` on `(space_id, grouped_day)`. One-time backfill for
        pre-existing rows (`ddl-auto: update` only adds the column, doesn't populate it):
        `PhotoRepository.backfillGroupedDay()` (idempotent, `IS NULL`-guarded), run from a
        `CommandLineRunner` bean in `IPhotoApplication.java` (matching `initDatabase`'s existing
        precedent there, not a new `ApplicationRunner` class).
        - Day-index queries (day/count/cover, modeled on the old month query's window-function/
          random-cover technique): `PhotoRepository.findDailyPhotoCountsWithCoverBySpaceId`
          (Library/Space) and `findDailyPhotoCountsWithCoverByAlbumId` (Album, joins
          `album_photos`) → `PhotoService.getDailyPhotoCounts`/`AlbumService.
          getAlbumDailyPhotoCounts` → new `model/DayBucketDTO.java`.
        - Per-day photo queries: `findBySpaceIdAndIsGarbageFalseAndGroupedDay` /
          `findByIsGarbageFalseAndAlbumsAndGroupedDay` → `PhotoService.getPhotosForDay` (goes
          through `buildDedupedPage`, same as the plain feed) / `AlbumService.
          getAlbumPhotosForDay` (no dedup, matching `getAlbumPhotos`'s existing behavior).
        - New endpoints: `GET /api/photos/days` + `/api/photos/day/{day}` (Library),
          `GET /api/photos/space/{spaceId}/days` + `/day/{day}` (Space, `PageResponse` envelope),
          `GET /api/albums/{id}/days` + `/photos/day/{day}` (Album, raw `Page` envelope matching
          its existing `/{id}/photos` sibling - a pre-existing envelope inconsistency between
          controllers, not introduced here).
        - Removed: `PhotoController.getTimeline`/`getPhotos`'s cursor params, `PhotoService.
          getTimeline`/`getPhotosAfter`/cursor `getPhotos` overload, `PhotoRepository.
          findMonthlyPhotoCountsWithCoverBySpaceId` + `findBySpaceIdAndIsGarbageFalseAndDateTaken
          {LessThan,GreaterThan}`, `model/TimelineMonthDTO.java`. `GET /api/photos` kept as a
          plain pageable listing (params simplified, endpoint itself not removed).
      - **Frontend**: new `hooks/useDayIndexedPhotos.js` (`days`/`photosByDay`/`dayPageState`/
        `loadDayPage`/`refreshDays`/`refreshLoadedDays`), used for Library/Album/Space only;
        `hooks/usePhotoData.js` simplified back down to just Garbage/Person's flat pagination
        (the `jumpToMonth`/`loadNewer`/`hasNewer`/cursor-switching logic it carried was Library-
        only and is now entirely dead). `PhotoGrid.jsx` gained a `dayIndex` prop - when present,
        renders one section per day with a single shared `IntersectionObserver` across every
        day's sentinel (not one observer per day - hundreds of populated days is realistic and a
        per-section observer wouldn't scale); when absent, falls back to the exact original
        flat-array code path unchanged (Garbage/Person). New `utils/photoListUpdates.js`
        (`removeFromFlatPhotos`/`removeFromPhotosByDay`) factors out the actual removal/
        duplicate-scrubbing logic `usePhotoActions.js`'s `handleMoveToGarbage` needs (see the
        bugfix entry above this one) so it works against either shape via a `removePhotosLocally`
        callback App.jsx defines. `App.jsx`: `libraryPhotoGrid` now branches on `isDayIndexedView`
        rather than being a single flat-props block, since `/asset/:photoId`'s `effectivePath` can
        resolve to either Library or Garbage depending on which backgrounded it; a new
        `effectivePhotos`/`flatDayIndexedPhotos` pair feeds anything that needs "the current
        view's whole collection" regardless of data source (Lightbox prev/next, scroll
        restoration, direct-asset-URL lookup) - easy to miss since `photos` used to just mean one
        thing everywhere. `AlbumDetail.jsx`/`SpaceDetail.jsx` simplified to take `dayIndex`
        directly instead of forwarding `photos`/`hasMore`/`setPage`. `LibraryTimeline.jsx`/
        `useMonthlyTimeline.js` deleted - no replacement "jump to month"/Memories UI built in this
        pass (all days are visible/scrollable up front now, which covers the core need); worth
        revisiting only if actually asked for.
      - **Known, accepted limitations**: duplicate-clustering only works within currently
        *loaded* photos (same limitation the old flat feed already had, just now per-day-scoped
        instead of per-loaded-pages-scoped); `refreshAndMaintainScroll` on a day-indexed view
        (`refreshLoadedDays`) only refreshes each already-loaded day's first page - a day with
        >100 photos and multiple pages loaded only gets its first page refreshed by the other
        photo actions (restore/move-to-main/permanent-delete/date-edit/edit-photo/unlink-
        duplicate), which don't get `handleMoveToGarbage`'s local in-place update treatment.
      - **Follow-up (user request): extended to Garbage and Person too** ("one view means one
        technology for all pages") - `usePhotoData.js`/`usePhotoData.test.js` deleted entirely,
        `PhotoGrid.jsx`'s flat-array fallback code path removed (the `dayIndex` prop is no longer
        optional - every consumer now builds one). Every photo-listing view in the authenticated
        app is day-indexed; only the guest/share flow (`SharedAlbumView.jsx`) still uses its own
        flat, page-number-paginated fetch (`useSharedAlbumPhotos.js`, hitting the separate
        guest-scoped `/api/albums/share/{albumId}/photos` endpoint - no user session to derive a
        day-index from, and never part of this request), adapted locally into a `dayIndex`-shaped
        object (bucketed by `groupDate`) so it can still hand its photos to the now day-index-only
        `PhotoGrid`.
        - **Backend - Garbage**: cross-space, same scope the old flat endpoint had (a user's
          garbage isn't scoped to one space). `PhotoRepository.
          findDailyPhotoCountsWithCoverBySpaceIdInAndIsGarbageTrue`/
          `findBySpaceIdInAndIsGarbageTrueAndGroupedDay` → `PhotoService.
          getGarbageDailyPhotoCounts`/`getGarbagePhotosForDay` → `GET /api/photos/garbage/days` +
          `/garbage/day/{day}` (replaces the old flat `GET /api/photos/garbage`, removed along
          with `getGarbagePhotos`/`findBySpaceIdInAndIsGarbageTrue`). No dedup treatment, matching
          the old endpoint.
        - **Backend - Person**: joined through `faces` instead of `space_id`/`album_photos`, since
          a person's photos can span multiple spaces - no existing precedent for this join in
          `PhotoRepository`, had to write it fresh. `findDailyPhotoCountsWithCoverByPersonId`/
          `findByFacesPersonAndGroupedDay` (deliberately no `is_garbage` filter, matching
          `findByFacesPerson`'s existing no-filter semantics) → `PhotoService.
          getPersonDailyPhotoCounts`/`getPersonPhotosForDay` → `FaceService.getPersonDays`/
          `getPersonPhotosForDay` → `GET /api/faces/person/{id}/days` + `/photos/day/{day}`
          (replaces the old flat `GET /api/faces/person/{id}/photos`, removed along with
          `FaceService.getPersonPhotos`). `findByFacesPerson`/`PhotoService.findByPerson` kept -
          still used by `FaceService.mapToPerson` purely for the person's total photo count.
        - **Frontend**: `useDayIndexedPhotos.js`'s `resolveDayIndexTarget`/`daysUrl`/
          `dayPhotosUrl` gained `garbage`/`person` branches. `PhotoGrid.jsx` lost its flat-mode
          `groups` construction, its own `IntersectionObserver`/bottom sentinel, and the
          `photos`/`hasMore`/`setPage`/`type`-for-grouping props - `dayIndex` is the only data
          source now. `PersonDetail.jsx` restructured like `AlbumDetail.jsx`/`SpaceDetail.jsx`
          (takes `dayIndex`, gates loading/empty state off `dayIndex.loadingDays`/`days.length`).
          `App.jsx`: `isDayIndexedView`/`usePhotoData` removed entirely, `effectivePhotos`/
          `removePhotosLocally`/`actualRefreshAndMaintainScroll` no longer branch on data source.
        - **Tests**: `useDayIndexedPhotos.test.js` gained garbage/person `resolveDayIndexTarget`
          and fetch-URL cases; `PhotoGrid.test.jsx`'s harness now wraps its fixture photos into a
          single-day `dayIndex` object instead of passing a flat `photos` array, same assertions.
      - **Follow-up (user request): split the count+cover query, cache Library/Space/Album's day
        index**. The single native query computing count+cover together (`count(*) over (partition
        by ...)` + `row_number() over (... order by random())`) forced a full sort of every
        matching row per partition just to answer "how many," even when the caller only wanted the
        count. Split into two queries per view (space/album/garbage/person, 8 total): a plain
        `GROUP BY groupedDay` JPQL count (index-friendly on `idx_photos_space_grouped_day`, no sort
        needed) and a separate native `DISTINCT ON (grouped_day) ... ORDER BY grouped_day, random()`
        cover query (Postgres idiom for "one random row per group," replacing the old
        `row_number()`-then-filter-`rn=1` trick). `PhotoRepository.DayBucketProjection` split into
        `DayCountProjection`/`DayCoverProjection`; `DayBucketDTO.zip(counts, covers)` (new static
        method) merges the two lists back into one DTO per day, tolerating a count with no matching
        cover (null `coverPhotoUrl`) rather than throwing.
        - **Caching**: new `service/DayIndexCache.java` (`@Component`, two plain
          `ConcurrentHashMap<UUID, List<DayBucketDTO>>` - this app runs single-instance, no need for
          a distributed cache) - caches `PhotoService.getDailyPhotoCounts` (Library/Space, keyed by
          spaceId) and `AlbumService.getAlbumDailyPhotoCounts` (keyed by albumId) only. Garbage/
          Person's equivalents (`getGarbageDailyPhotoCounts`/`getPersonDailyPhotoCounts`)
          deliberately NOT cached, per explicit instruction - low-traffic pages, not worth the
          invalidation bookkeeping. Kept as its own component rather than fields on
          `PhotoService`/`AlbumService` directly so `PhotoUploadService`/`ImmichImportService` (which
          need to evict a space's entry after creating a photo) can depend on it too without a
          circular dependency (`PhotoService` already depends on `PhotoUploadService`).
        - **Invalidation call sites** (evict-on-mutation, no TTL): `PhotoUploadService.
          processTheAsset` (covers both a regular upload AND "save as new" - `EditPhotoService`'s
          `saveAsNew` path routes through the same `PROCESS_UPLOAD` task/upload code, one hook
          point for both), `ImmichImportService.persistPhotoAndMetadata` (import), `PhotoService.
          moveAssetsToGarbage`/`restoreAssetsFromGarbage` (evict the affected photo's space),
          `moveAssetsToMain` (evict both the source space, captured before reassignment, and the
          destination Main space), `updatePhotoDate` (a `dateTaken` edit can move a photo to a
          different `groupedDay` bucket entirely), `AlbumService.addPhotosToAlbum`/
          `removePhotosFromAlbum` (evict that album - also covers `moveAssetsToGarbage`'s existing
          internal call to `removePhotosFromAlbum` for free, no separate hook needed there).
          Explicitly not evicted (judged harmless): permanent-delete of an already-garbage photo
          (doesn't change the non-garbage day-index this cache holds) and a permanently-deleted
          space/album's now-orphaned cache entry (that id is never queried again - a bounded,
          negligible memory cost, not a correctness issue).
        - **Not yet runtime-verified**: the JPQL `GROUP BY`/`JOIN p.faces`/`JOIN p.albums ... a IN
          :albums` queries are only javac-clean (query syntax itself isn't validated until
          Hibernate parses it against a live app context) - needs a real restart against Postgres
          to confirm before trusting this in production.
      - **Follow-up (user request): dropped the per-day cover photo, added a "Memories" bar
        instead**. Turned out `coverPhotoUrl` (computed for every single day via a `DISTINCT ON`
        query, even after the count/cover split above) was never actually rendered anywhere in the
        frontend - a leftover carried over from the old `TimelineMonthDTO`/`LibraryTimeline.jsx`
        month-timeline this whole feature replaced, which DID show a per-month cover in its "jump
        to month" strip, a UI the day-index plan explicitly chose not to rebuild. Removed entirely:
        `DayBucketDTO` is now just `(day, count)`; `DayCoverProjection` and all four
        `findRandomCoverPhotoPerDay*` native queries deleted; `PhotoRepository/AlbumService/
        PhotoService`'s day-count methods simplified back down to one query + `DayBucketDTO.from(...)`
        (no more `zip`).
        - **New feature in its place**: Library-only horizontal bar showing one random photo per
          configured "N months ago" offset (today minus N months, matched against `groupedDay`) -
          "1 month ago," "6 months ago," "1 year ago," etc. Offsets are configurable, not
          hardcoded: `IPhotoProperties.Memories.monthsAgo` (`iphoto.memories.months-ago` in
          `application.yml`, defaults `[1, 6, 12, 36, 60, 120]`).
        - **Backend**: `PhotoRepository.findRandomPhotoBySpaceIdAndGroupedDay(spaceId, day)` (native,
          `ORDER BY random() LIMIT 1`, exact single day - not the DISTINCT-ON-across-many-days
          pattern the deleted cover queries used) → `PhotoService.getMemories(spaceId)` (one lookup
          per configured offset, skips any offset with nothing that day rather than returning an
          empty slot; deliberately NOT cached - each lookup is already a cheap indexed point query,
          and today's date only changes once a day anyway) → new `model/MemoryDTO.java`
          `(monthsAgo, day, Photo)` (embeds the full `Photo` DTO, not just a thumbnail URL, so the
          frontend can open it directly in the Lightbox without a second round-trip) →
          `GET /api/photos/memories` (`PhotoController`, Main space only, same pattern as
          `GET /api/photos/days`).
        - **Frontend**: new `hooks/useMemories.js` (fetch-once-on-mount, mirrors `usePersons.js`'s
          shape) + `components/MemoriesBar.jsx` (arrow-scrollable strip, structurally mirrors
          `PersonStrip.jsx` - rectangular photo thumbnails instead of circular avatars, one label
          per item via the already-existing-but-previously-unused `memoryMonthsAgo`/
          `memoryYearsAgo` i18n functions, a leftover from the old timeline that finally has a
          consumer). Clicking a memory calls the same `handlePhotoClick` every grid card uses, with
          the memory's embedded `Photo` object - works even though that photo isn't part of the
          currently-loaded day-index pages, no special-casing needed. Rendered in `App.jsx` directly
          above `PhotoGrid` inside `libraryPhotoGrid`, gated on `selectedMenu === 'library'` (not
          shown for the Garbage-embedded instance of the same shared JSX, nor Album/Space/Person).
        - **CSS**: `.memories-strip*` classes mirror `.person-strip*` almost exactly; the sticky
          `.day-header` z-index conflict `.person-strip` already had to work around (see its own
          CSS comment) needed the same fix here, but via an adjacent-sibling selector instead of
          `:has()` - `MemoriesBar` is a sibling of `PhotoGrid`'s own `.content-body`, not nested
          inside a shared wrapper the way `PersonStrip` is inside `PersonDetail.jsx`'s.
      - **Follow-up (user request): show `photosPerBlock` photos per offset instead of 1, auto-
        cycled**. `findRandomPhotoBySpaceIdAndGroupedDay` → `findRandomPhotosBySpaceIdAndGroupedDay
        (spaceId, day, limit)` (`LIMIT :limit` instead of `LIMIT 1`); `MemoryDTO.photo` → `MemoryDTO.
        photos` (a list); new `IPhotoProperties.Memories.photosPerBlock` (default 5, same
        configurable-not-hardcoded treatment as the offsets themselves). Frontend: new `MemoryItem`
        sub-component (its own `setInterval`-driven rotation state/cleanup, independent per block)
        cycles through `memory.photos` every 3s with a CSS fade-in (`key={current.id}` forces a
        remount, restarting the animation); clicking opens whichever photo is *currently showing*,
        not always the first.
      - **Follow-up (user request): day-tolerance window instead of an exact-day-only match**.
        User's framing: "6-2d+3d which is equals to 6+3d-2d instead of 6" - widen the search
        around an anchor day that might not have an exact hit, rather than requiring one. New
        `model/MemoryOffset.java` (`record(monthsAgo, beforeDays, afterDays)` + `parse(String)`) -
        a plain integer stays exact-day-only (`beforeDays=afterDays=0`); appending `-Nd`/`+Nd`
        tokens in EITHER order widens the window that many days earlier/later than the anchor.
        `IPhotoProperties.Memories.monthsAgo` (`List<Integer>`) → `.offsets` (`List<String>`,
        defaults unchanged/still exact - `[1, 6, 12, 36, 60, 120]`, now as strings so the tolerance
        syntax fits the same list). `PhotoRepository.findRandomPhotosBySpaceIdAndGroupedDay(spaceId,
        day, limit)` → `...GroupedDayBetween(spaceId, startDay, endDay, limit)` (`BETWEEN` instead
        of `=` - an exact day is just the degenerate case where `startDay == endDay`, one query
        path for both). `PhotoService.getMemories` parses each configured entry via
        `MemoryOffset.parse`, skipping (with a `log.warn`, not a 500) any entry that fails to parse
        rather than letting one bad config string break the whole feature. `MemoryDTO.day` still
        reports the ANCHOR day (not the actual day of whichever photo was found) - unchanged
        meaning, still purely informational since the frontend doesn't currently render it.
        - **Not tested**: no backend JUnit test added for `MemoryOffset`'s parsing (order-
          independence, missing tokens, invalid input) - this project's only test sources are
          integration tests (`AbstractIntegrationTest`/`PhotoLifecycleIntegrationTest`), and this
          sandbox has no working `mvn` to actually run anything against them; every other
          backend-only change this session was verified the same way (javac compile only), so
          this stays consistent with that rather than being a one-off addition that can't even be
          confirmed to pass.
        - Verified: whole backend `javac` compile clean (main + test sources). No frontend change
          (day-tolerance is backend-only - the frontend already just renders whatever `photos`
          array comes back, regardless of how the window that produced them was computed). **Not
          yet exercised against the live app** - needs a real restart, and the actual date-math
          (order-independence of `-Nd`/`+Nd`, the `BETWEEN` query) hasn't been observed against a
          real Postgres instance.
      - **Follow-up (user request): clicking a memory scrolls to its day instead of opening the
        Lightbox**. Motivated by the tolerance window above - once a memory's photos can span
        several days, "open in the Lightbox" was less useful than "take me to that day in the
        grid" would be (matches `TimelineScrubber`'s own jump behavior). Each auto-cycled photo
        in a block can be on a DIFFERENT actual day (tolerance draws from the whole window, not
        one day), so there's no single well-defined day for a whole block - only for whichever
        photo is currently shown, same principle the existing "click opens whichever photo is
        showing" behavior already followed. New `MemoriesBar.jsx#dayOf(photo)` derives the plain
        `yyyy-MM-dd` day from `photo.fullDateTime`'s first 10 characters (guaranteed to be exactly
        that, since `fullDateTime` is `DateTimeFormatter.ISO_LOCAL_DATE_TIME` and Memories photos
        always have a real `dateTaken` - they're only ever found via a `groupedDay` query) rather
        than adding a dedicated backend field just for this. `App.jsx` gained `seekToMemoryDay`,
        replicating `PhotoGrid.jsx#seekToDay`'s instant-`scrollIntoView`-plus-eager-`loadDayPage`
        behavior but via `document.querySelector('[data-day-group="..."]')` instead of a local ref
        - `MemoriesBar` is a sibling of `PhotoGrid`, not a child, and only one Library `PhotoGrid`
        is ever on screen at a time, so a global query is unambiguous. `MemoriesBar`'s `onPhotoClick`
        prop renamed to `onSeekToDay` throughout.
      - Verified: `npm test -- --run` 105/105, `npm run build` clean, lint unchanged (the one new
        `useCallback` warning matches an already-accepted pattern twice elsewhere in this same
        file - a stable method dependency instead of the whole `dayIndexed` object, which is a
        fresh literal every render). No backend change. **Not yet clicked in a real browser.**
- [x] **Feature** (user request): a side scrubber over day-indexed browsing so the user can jump
      straight from one day to a distant one without scrolling (and so without fetching) through
      every day in between; scrolling up after such a jump needed to start fetching too, not just
      scrolling down.
      - **`components/TimelineScrubber.jsx`** (new): fixed vertical strip on the right edge, one
        year label per first occurrence of a new (older) year in `dayIndex.days` (already sorted
        newest-first). Position is index-based (`index / (days.length - 1)`), not a real
        pixel-measured scale - a day's actual rendered height depends on how its photos wrap,
        which isn't cheap to know for every day up front. Pointer Events (down/move/up, unifies
        mouse/touch/pen) rather than separate mouse/touch handlers. Dragging only updates a live
        hover tooltip (day string at the current pointer position); the actual jump only fires
        once, on release - firing it on every intermediate position during a slow drag would load
        every day swept over, defeating the whole point. A plain click still jumps immediately
        (down+up at the same position naturally produces one hover value, committed on release).
      - **`PhotoGrid.jsx`**: added `data-day-group={day}` on each day's wrapper `div` (already
        rendered for every day up front regardless of load state, per the day-index design) so a
        jump can `querySelector` straight to any day's section, loaded or not. New `seekToDay(day)`
        callback: an INSTANT `scrollIntoView` (deliberately not `behavior: 'smooth'` - an animated
        scroll would transiently intersect every sentinel it passes over on the way, fetching
        everything in between exactly like the feature is meant to avoid) plus an eager
        `dayIndex.loadDayPage(day)` call, so the jump doesn't wait on the IntersectionObserver to
        notice the new scroll position before starting to load. Rendered unconditionally
        (`<TimelineScrubber days={dayIndex.days} onSeek={seekToDay} />`, no-ops itself when
        `days.length < 2`) - lives in `PhotoGrid.jsx` itself rather than being wired per-view like
        `MemoriesBar`, so it applies everywhere day-indexed browsing already does (Library/Album/
        Space/Garbage/Person/the share-guest flow's local `dayIndex` adapter) with no extra wiring.
      - **Scroll-up fix**: the day-sentinel `IntersectionObserver`'s `rootMargin` was
        `'0px 0px 1500px 0px'` (prefetch margin below the viewport only) - every not-yet-loaded
        day already renders its own sentinel regardless of position (same reason `seekToDay`'s
        `querySelector` above works for any day), so scrolling UP after a jump landed on
        previously-unloaded sentinels with no prefetch margin above them at all, only loading once
        a section was already fully on screen. Changed to `'1500px 0px 1500px 0px'` (symmetric).
- [x] **Feature** (user request): two new bulk photo-selection actions, available everywhere a
      multi-select photo grid already exists — Library, Album detail, Space detail (all three
      share `components/Header.jsx`'s bulk-action bar, gated per-button by the capability flags
      `PhotoService.getSelection`/`PhotoSelectionDTO` returns from `POST /api/photos/selected` —
      see specification.md's note on this contract, and how e.g. `canBeDeleted`/
      `canBeMovedToAlbum` already drive `handleMoveToGarbage`/`setShowAddToAlbumModal` there).
      User confirmed the open design question below: bulk-apply one exact timestamp to every
      selected photo (not a relative offset).
      1. **Bulk "Edit Date Taken"**: `canEditDate` flag on `PhotoSelectionDTO` (true whenever the
         selection is non-empty, no garbage restriction). **No new backend endpoint** — reuses the
         existing `PATCH /api/photos/{id}/date` once per selected photo via `Promise.all`
         (`usePhotoActions.js#handleBulkUpdateDate`), the same pattern
         `useAlbumManagement.js#handleAddPhotosToAlbums` already uses for its own bulk action. New
         `components/EditDateModal.jsx` (date/time picker + "Apply to N photos"), modeled on
         `AddToAlbumModal.jsx`. New `Header.jsx` button (calendar/clock icon) opens it.
      2. **Bulk "Download"**: `canDownload` flag on `PhotoSelectionDTO` (same non-empty-selection
         gating). Turned out `GET /api/photos/{id}/original` could **not** be reused directly as
         originally sketched — it's used as an `<img src="...">` for in-page rendering all over the
         frontend (Lightbox full-res view, `UploadPreviewModal`, `DuplicateClusterModal`,
         `SharedLightbox`), and a browser won't render an image inline from a response marked
         `Content-Disposition: attachment`. Built a genuinely new `GET /api/photos/{id}/download`
         endpoint instead for the single-photo case (`PhotoController#download`/`serveAsAttachment`).
         Multiple selected photos: new `POST /api/photos/download` (body = list of ids, not
         GET+query-string — a large selection could exceed a comfortable URL length), streaming a
         zip via `StreamingResponseBody` wrapped around a plain `ZipOutputStream`
         (`PhotoController#downloadMultiple`) — never buffers the whole zip in memory. New
         `PhotoService.findOwnedByIds(user, ids)` is the authorization boundary here (mirrors the
         ownership check `deleteAssets` already does for bulk delete) — silently drops any id the
         caller doesn't own rather than serving it, so a client can't read another user's files by
         passing arbitrary ids. Zip entry name = `PhotoEntity.fileName`, de-duplicated with an
         id-suffix fallback (`PhotoController#uniqueEntryName`) for the real edge case of two
         selected photos sharing a filename (duplicates across spaces/albums, or two
         differently-cropped copies). Frontend (`usePhotoActions.js#handleDownload`): a single id
         sets `window.location.href` directly (no fetch, browser handles it); multiple ids fetch the
         zip as a blob, then click a temporary `<a download>` pointed at an object URL.
         `Header.jsx` new button (`Download` icon) calls it directly, no modal.
      Backend: `PhotoServiceTest` covers `findOwnedByIds` ownership filtering and both new
      `Content-Disposition`/zip-entry behaviors (12/12 pass); full suite 103/103 pass. Frontend:
      `usePhotoActions.test.js` covers `handleBulkUpdateDate` (success/partial-failure) and
      `handleDownload` (single vs. multi-id branch); full suite 112/112 pass; `npm run build`
      succeeds. Not yet manually clicked through in the running app.
- [x] **Feature** (user request): drag-to-reorder photos within a day. Hovering a `PhotoGrid.jsx`
      card (Library/Album/Space/Person — not `SharedAlbumView.jsx`'s public grid) shows a
      translucent circular drag handle (`lucide-react`'s `Move` icon); dragging it before/after
      another card in the SAME day re-stamps its `dateTaken` so the grid's existing newest-first
      sort reflects the new position. Dragging one of several currently-selected cards moves the
      whole selection together (dimmed while dragging), preserving their relative order; any
      selected id from a *different* day is silently excluded (the same-day constraint).
      - Confirmed math via a worked example (grid is newest-first): dropping X between P (ends up
        displayed just before X, newer) and Q (ends up displayed just after X, older) sets
        `X.dateTaken = Q.dateTaken + 1s` — the anchor is the neighbor that ends up **after** the
        moved item(s), not before. Multi-item drags cascade seconds outward from that anchor,
        preserving original relative order; a tight gap (would collide with/exceed the far
        neighbor) falls back to a millisecond cascade instead. A day-boundary clamp guards against
        spilling into a different calendar day. All pure, exhaustively unit-tested in new
        `utils/photoReorder.js#computeReorderDates` (9/9 tests) — kept out of `PhotoGrid.jsx`
        itself since jsdom's native HTML5 drag-and-drop simulation is poor.
      - Backend discovery: `PhotoController`'s `df` formatter for `PATCH /api/photos/{id}/date`
        was `DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")` — **minute precision only**, unable
        to represent the second/millisecond deltas this feature needs. Widened to
        `DateTimeFormatter.ISO_LOCAL_DATE_TIME` (backward compatible — seconds/nanos are optional
        in that formatter, so the `datetime-local`-driven single/bulk edit-date flows keep parsing
        identically). This meant **no new backend endpoint** was needed at all — the reorder
        feature reuses the same existing PATCH endpoint, one call per moved photo via
        `Promise.all` (`usePhotoActions.js#handleReorderPhotos`), exactly like the bulk
        "Edit Date Taken" feature above reuses it. Unlike that sibling handler, this one
        deliberately does NOT clear the active selection on success.
      - Only a drag handle (not the whole card) is draggable, so existing click-to-select/
        open-lightbox behavior is untouched. Only PRIMARY photos participate (both as handles and
        drop targets) — a duplicate cluster's non-primary members aren't independently
        reorderable. Desktop-only (no touch-device polyfill added).
      - Backend: extended `PhotoServiceTest`'s existing date-update test with a full-precision-
        string case (regression case for the old minute-only format kept alongside it); full suite
        103/103 pass. Frontend: new `photoReorder.test.js` (9/9) plus 4 new
        `usePhotoActions.test.js` cases for `handleReorderPhotos`; full suite 125/125 pass;
        `npm run build` succeeds. Not yet manually clicked through in the running app.
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
      x/y/width/height for that person/photo pair — `FaceDTO` has the box but no `unlinkedPhotoId` to join
      on, and `getPersonFaces`/`getPersonPhotos` are two separate paginated endpoints, not
      guaranteed to be in step. Cleanest fix is probably to extend `PersonController`'s
      `getPersonPhotos` path so each returned `Photo` (or a small new DTO wrapping it) carries that
      person's face box for it directly, computed server-side, rather than the frontend trying to
      stitch two separate paginated lists together. Then in `PhotoGrid`, the box needs to scale from
      the face's coordinates (recorded against the photo's *full* resolution) down to whatever size
      the grid thumbnail actually renders at — same scaling math the Lightbox already does in
      `recalculateImageSize`, reusable if extracted.
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

- [x] **Feature (backend-heavy)**: reorganized on-disk photo storage into `{year}/{month}/`
      subfolders (was one flat directory each for originals/thumbnails — "not good practice",
      user's words, and about to get worse once Immich import could add thousands more files),
      plus a full **Immich migration** admin feature: assets/metadata/GPS/albums/album-level
      shared links imported day-by-day from a self-hosted Immich server, preserving Immich's own
      asset UUIDs as iphoto's `Photo` ids. See specification.md for the full design (new
      "Immich migration" section) — summarizing here:
      - **Storage reorg**: new `service/PhotoStoragePathResolver.java` is the single owner of the
        `{root}/{year}/{month}/` scheme (off `dateTaken`), used by both `PhotoUploadService` (had
        to be reordered so metadata extraction — which sets `dateTaken` — runs before the file is
        moved to its date-based folder, not after) and the new Immich per-asset ingestion path.
        Safe against a later "edit photo date" because nothing in the codebase reconstructs a
        path from date after creation — `PhotoEntity.originalPath`/`thumbnailPath` are always read
        back verbatim. `ThumbnailService.generateThumbnail` gained a `dateTaken` param; a second
        `regenerateThumbnailInPlace` overload was added for `EditPhotoService`'s "overwrite
        original" edit path, which must keep writing to wherever the thumbnail already lives, not
        move it. Optional backfill for photos already stored flat: `StorageMigrationService`
        (idempotent, per-photo no-op if already organized) behind
        `POST /api/admin/storage/migrate-flat-files` — deliberately **not** run automatically at
        startup; old photos stay flat forever until an admin clicks it, which is fine since reads
        never depend on the folder convention.
      - **Immich migration, schema**: new `ImmichImportDayEntity`/`ImmichImportDayRepository`
        (table `immich_import_days`, not space-scoped — indexes one external server, not per-user
        data) is both the "table of days with photos on Immich" the user asked for and the
        per-day progress tracker (`immichAssetCount`/`queuedCount`/`importedCount`/
        `skippedCount`/`failedCount`/`status`). `AlbumEntity`/`ShareEntity` each gained a nullable
        `externalSource`/`externalId` pair (+ unique constraint) so a re-run can find-or-create
        the same album/share instead of hitting `AlbumService.createAlbum`'s/
        `ShareService.createShare`'s name-collision throw. New `TaskType.PROCESS_IMMICH_ASSET`
        (one task per asset, mirroring `PROCESS_UPLOAD`'s shape) reuses the existing
        `TaskWorkerManager` scheduled-poller/virtual-thread infrastructure — the only async-job
        mechanism in the codebase — rather than inventing a new one.
      - **UUID preservation**: trivial for photos — `PhotoEntity.id` already has no
        `@GeneratedValue` (it's assigned explicitly at task-creation time, same as
        `UploadController` does with `UUID.randomUUID()`); Immich import just uses the Immich
        asset's own UUID in that slot instead. Albums needed the new `externalId` column instead,
        since `AlbumEntity.id` **is** `@GeneratedValue`.
      - **`ImmichClient`** (`service/immich/`, new `RestClient`-based wrapper, `x-api-key` auth):
        `/timeline/buckets?size=DAY` for the day-index, `/assets/{id}` + streamed
        `/assets/{id}/original` download, `/albums` + `/albums/{id}`, `/shared-links`,
        `/assets/statistics` (for the coverage check below). **Important caveat**: several
        response shapes (day-bucket fields, `exifInfo` field names, the exact per-day
        asset-listing endpoint) are BEST-EFFORT — built from Immich's public API docs, not
        verified against a live server (none was available while building this). Chose
        `POST /search/metadata` with day-boundary `takenAfter`/`takenBefore` over
        `/timeline/bucket` for per-day asset listing specifically because the latter's response
        shape is less certain/more version-sensitive. Every DTO in `model/immich/` is annotated
        `@JsonIgnoreProperties(ignoreUnknown = true)` and flagged in a comment where verification
        is needed — **this is the one part of this feature that still needs a real smoke test
        against the user's actual Immich server** (which was the whole point of day-by-day import
        in the first place — small, cheap, safe to iterate on). Outbound calls are throttled via
        a `Semaphore(8)` inside `ImmichClient` since `TaskWorkerManager`'s poller has no
        concurrency limit of its own.
      - **`ImmichImportService`**: `syncDayIndex` upserts the day table from Immich;
        `triggerDayImport` queues one `PROCESS_IMMICH_ASSET` task per not-yet-imported asset for
        a day (skips assets that already exist locally, or that already have a non-`FAILED` task
        in flight — a `FAILED` one is cleared and its earlier failure-count contribution undone,
        since that's exactly what a retry should replace); `processAssetImportTask` does the
        actual download/hash-dedup-check/metadata-extraction/`PhotoEntity` creation (hooking the
        new photo into the existing hash/duplicate/face-detection task pipeline via
        `TaskMapper.Action.ON_CREATE_PHOTO_ACTION`, same as any normal upload); day-row counters
        are updated via atomic `@Modifying` increments (many assets for one day finish
        concurrently on different virtual threads) in a `REQUIRES_NEW` transaction, with
        `queuedCount` kept as the day's *total* known size (stable across re-runs) so cumulative
        counters stay meaningful across multiple trigger calls, not reset per-call.
      - **`ImmichAlbumImportService`**: recreates Immich albums (find-or-create by `externalId`)
        and links already-imported photos into them (`AlbumService.addPhotosToAlbum`, which
        requires the photo to already exist and be in a MAIN space — so this must run *after*
        day-imports, and does, per the UI's ordering hint); recreates album-level Immich shared
        links as iphoto `ShareEntity` rows. Immich shared links pointing at individual assets
        (not a whole album) have no iphoto equivalent (iphoto shares are album-only) — skipped
        with a warning surfaced in the summary, not silently mis-mapped. Runs each album/link in
        its own transaction (via `TransactionTemplate`, not one `@Transactional` spanning the
        whole run) so one broken item can't mark the entire run rollback-only.
      - **Coverage check** (added mid-build after the user pointed out Immich will be *deleted*
        once migration finishes, raising the stakes on completeness): `/timeline/buckets` only
        surfaces assets with a determinable date — an asset with no usable date wouldn't appear
        in any day bucket and would be silently missed. `GET /api/admin/immich/days` now returns
        `{days, coverage}` where `coverage` compares Immich's true total asset count
        (`/assets/statistics`) against the sum of day-bucket counts actually known locally,
        surfaced as a visible warning banner on the admin page when they don't match — meant to
        be checked *before* deleting the Immich server, not after.
      - **Frontend**: new admin-only `/admin/immich` page (`ImmichImportManagement.jsx` +
        `hooks/useImmichImport.js`), gated the same way `/admin/users` already is
        (`{userRole === 'ADMIN' && (...)}` in `App.jsx`/`Sidebar.jsx`, new `Download` sidebar
        icon). Table of days (reuses `UserManagement.css`'s generic container/table classes, like
        `ShareLinksManagement.jsx` already does) with an Import/Retry button per row; per-day
        polling is modeled directly on `UploadManager.jsx`'s `pollTaskStatus` (`setInterval`/
        `clearInterval` on a terminal status) — no new polling pattern invented. "Import Albums &
        Shares" button appears once at least one day is `COMPLETED`/`PARTIAL`.
      - **Not in scope** (per explicit user confirmation): people/faces and favorites are not
        migrated from Immich — only assets, EXIF/GPS metadata, albums, and album-level shares.
      - Verified: `mvn`-equivalent `javac` compile clean (only the pre-existing unrelated
        `SpringContext.java` unchecked-operations note); `npm run build` clean; full frontend
        suite 86/86 pass. **Not yet verified against a live Immich server** — no credentials were
        available while building this; the `ImmichClient`/DTO layer specifically should be
        treated as the first thing to smoke-test once the user runs this against their real
        server (see the "IMMICH_URL/IMMICH_API_KEY not configured" warning `ImmichClient` logs at
        startup if those env vars are blank). Backend also not yet restarted/verified live for
        the same reason noted on earlier entries in this file (IDE-debugger-managed process).
- [x] **Backend test infra (new)**: first backend tests in the project (`src/test/java` didn't
      exist before) — a real end-to-end integration test covering the photo lifecycle: log in ->
      create a Space -> upload a photo straight into it -> move it to Main -> create an Album and
      add the photo to it -> move it to Garbage -> permanently delete it. Driven entirely through
      MockMvc against the real controllers/security filter chain, not by calling services
      directly, and against a **real Postgres** via Testcontainers (`@ServiceConnection`) rather
      than H2 — this app is Postgres-only in practice (native window-function queries, BYTEA
      columns, hardcoded dialect) and has no Flyway/Liquibase (`ddl-auto: update`), so a real
      Postgres instance is both more representative and no extra setup burden than an in-memory
      substitute. New `pom.xml` test-scope deps: `spring-boot-testcontainers`,
      `testcontainers:junit-jupiter`, `testcontainers:postgresql` (versions managed by the Spring
      Boot parent BOM, no explicit versions needed — matches this pom's existing style). New
      `AbstractIntegrationTest` (base class: `@SpringBootTest`+`@AutoConfigureMockMvc`+
      `@Testcontainers`, and a `@DynamicPropertySource` pointing `iphoto.storage.*` at a
      `Files.createTempDirectory` throwaway directory — tests must never write into the real
      storage path configured in `application.yml`) + `PhotoLifecycleIntegrationTest`.
      - **Real bug found and fixed along the way**: `IPhotoApplication`'s `CommandLineRunner`
        bootstrapped the admin account via a raw `userRepository.save(admin)`, bypassing
        `UserService.createUser` — which is also the only place a user's default MAIN space gets
        created. On a genuinely fresh database, the bootstrapped admin therefore had **no space
        at all**, and `SpaceService.getDefaultSpaceForUser(admin)` (used by upload, album
        creation, etc.) would return null — this had never been exercised in the user's own
        environment since their admin account has existed for a long time already, but would
        break a fresh install outright. Fixed by routing the bootstrap through
        `UserService.createUser(...)` instead of duplicating the save/encode logic inline, so the
        default space is created the same way it is for any other user.
      - Test-only per-asset upload processing note: `PROCESS_UPLOAD` tasks are normally picked up
        by `TaskWorkerManager`'s `@Scheduled(fixedDelay=5000)` poller — the test calls
        `taskWorkerManager.pollPendingTasks()` directly right after upload (same code path the
        scheduler itself would run, just not idling for the 5s timer) and then polls
        `GET /api/tasks/{id}` with a short bounded retry loop as a safety net for the async
        virtual-thread dispatch this triggers.
      - Album/Space assertions are made through the real HTTP endpoints (`GET
        /api/photos/{id}/space`, `GET /api/albums/{id}/photos`), not by calling
        `@MainSpaceLimited` repository methods directly from the test's own thread — that
        annotation's AOP filter resolves the current user from `SecurityContextHolder`, which
        only exists for the duration of an actual (mocked) authenticated HTTP request, not a bare
        JUnit thread. Garbage/permanent-delete assertions use `PhotoRepository.findById`
        directly instead, since that method is deliberately unfiltered.
      - **Not yet actually executed**: this sandbox has no `mvn` binary and no JUnit Platform
        launcher jar cached locally, so this was only verified via `javac` compiling cleanly
        against the full dependency classpath (including the new Testcontainers jars, already
        present in `~/.m2`) — never actually run. Docker and a `postgres:17` image are present
        locally (a `georges-db-1` container already uses that image), so Testcontainers should be
        able to start its own isolated container without a fresh pull, but this still needs a
        real `mvn test` (or IDE test run) to confirm it genuinely passes. Please run it and report
        back if anything fails — happy to fix promptly given how carefully this was derived from
        the actual controller/service code.
- [x] **Docker + CI**: new root `Dockerfile` (multi-stage: `maven:3.9-eclipse-temurin-25` build
      stage, `eclipse-temurin:25-jre` runtime stage) and `.github/workflows/docker-image.yml`
      (test job, then a build-and-push job publishing to GHCR). Also `.dockerignore`.
      - **Discovery while picking the base image**: `pom.xml`'s `maven-compiler-plugin` targets
        release **25** explicitly (`<source>25</source>/<target>25</target>`), distinct from — and
        newer than — the `<java.version>21</java.version>` property. The system `javac`/`java` on
        this machine's PATH is 21, but there's a JDK **26.0.1** under `~/.jdks` (IntelliJ's own
        managed install), which is what the project actually gets compiled with day to day
        (JDK 26 can target release 25 fine) — the CI workflow and Dockerfile both use JDK 25
        explicitly to match the pom's real contract, not the unrelated system JDK 21.
      - CI runs `mvn test` (the new Testcontainers-based integration test from the entry above)
        as its own job on the runner directly — **not** inside `docker build` — since
        Testcontainers needs real Docker access that a build step doesn't have without
        Docker-in-Docker; GitHub's `ubuntu-latest` runners already have Docker available, so this
        needs no extra setup. The image build only happens if that job passes.
      - Runtime env vars (Spring's relaxed env-var binding, no `application.yml` changes needed):
        `IPHOTO_STORAGE_BASE` (image sets a default of `/data/iphoto`, mounted as a volume —
        override if you want it elsewhere), `SPRING_DATASOURCE_URL`/`_USERNAME`/`_PASSWORD`
        (**must** be overridden for any real deployment — `application.yml`'s
        `localhost:5432/postgres/password` default is only reachable with host networking),
        `IPHOTO_ADMIN_PASSWORD`, `IMMICH_URL`/`IMMICH_API_KEY` (only if using the Immich import
        feature).
      - **Not verified end-to-end** (no way to actually run a Docker build or GitHub Actions from
        this environment): the runtime stage's `apt-get install libgomp1 libsm6 libxext6
        libxrender1` for OpenCV/FFmpeg native-library loading is a best-effort guess based on
        common javacv/OpenCV Docker deployment knowledge, not confirmed against a real container
        run of this exact app — if face detection/thumbnailing/duplicate-hashing throws a native
        `UnsatisfiedLinkError` in the container, start there. Separately, `ai.djl.pytorch:
        pytorch-model-zoo` has no bundled native engine in `pom.xml` (no `pytorch-native-*`
        dependency), so DJL will try to download the PyTorch native engine from the internet on
        first use at runtime — pre-existing app behavior, not introduced by Docker, but the
        container needs outbound internet for that first call to succeed. GHCR package
        visibility: a fresh package published via `GITHUB_TOKEN` isn't reliably private by
        default just because the workflow does nothing else to configure it — check/set it
        explicitly in the package's settings after the first push (linked in a workflow comment).
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

- [x] **Feature** (user request): admin on/off switch for heavy task processing, replacing the old
  one-shot "Activate Face Recognition" admin button; heavy tasks isolated into their own,
  much-less-frequent scheduler. User added `TaskType.ACTIVATE_HEAVY_TASKS` themselves before
  this request - a control record, not real work, `heavy=false` so it still flows through the
  light poller/thread pool.
  - **Backend - the switch itself**: exactly ONE `ACTIVATE_HEAVY_TASKS` `TaskEntity` ever
  exists (confirmed with the user after an initial pass that instead created a new row per
  toggle) - `AdminService.activateHeavyTasks(boolean)` finds it
  (`TaskRepository.findFirstByTaskTypeOrderByCreatedAtDesc` - tolerant of more than one row
  existing, e.g. from the earlier per-toggle-row design if this ever ran against a live DB
  with history) and updates its `payload` (`{"activate": "true"|"false"}`) in place,
  resetting `status`/`executeAt` so the light poller still picks it up and marks it
        `COMPLETED` again like any task's normal lifecycle; only creates a new row via
        `taskManagerService.createTaskEntity` the very first time, when none exists yet.
        `AdminService.isHeavyTasksActivated()` reads that row and parses its `activate` field
  (Jackson's `JsonNode.asBoolean()` handles both a real boolean and a `"true"/"false"` string
  transparently); no row yet defaults to `false` - opt-in, not opt-out. `TaskType.
        ACTIVATE_HEAVY_TASKS` is `canBeReplayed=false` (a global control record, `photoId` always
        `null`, isn't a per-photo task type `AdminService.updateImageTaskToDo` should ever touch -
  see that enum constant's own comment). New `GET`/`POST /api/admin/heavyTasks`
        (`AdminController`) replace the old `GET /activateFaceRecognition`.
        `TaskWorkerManager.processTask` gained a trivial `ACTIVATE_HEAVY_TASKS` branch
  (`task.setStatus(COMPLETED)`, no real work) - required because this task type is still
        `heavy=false`, so the light poller picks it up and dispatches it like any other task;
  without a branch it would sit stuck in `PROCESSING` forever (no case in the switch ever
  moves it to a terminal status).
  - **Backend - isolated heavy scheduler**: `TaskWorkerManager.pollHeavyTasks`, new
        `@Scheduled(fixedDelay = 300_000)` (5 minutes, vs the light poller's unchanged 5 seconds) -
  checks `adminService.isHeavyTasksActivated()` first; if not activated, returns immediately
  with no query/dispatch at all. If activated, same fetch-and-submit logic
        `pollLightPendingTasks` used to do for heavy tasks, moved here verbatim. The heavy-pool
        `MaxPoolSize` 3⇄8 adjustment heuristic (reacting to light-queue pressure) stayed in the
  *light* poller (`adjustHeavyPoolSizeForLightLoad`) since it needs to react at the light
  poller's 5-second cadence, not the heavy poller's new 5-minute one.
  - **Bootstrap gap this created, and its fix**: the old admin button was the ONLY place that
  ever created the first `FACE_RECOGNITION` task (which perpetually reschedules itself 30s
  after each run once started - see `TaskWorkerManager#processTask`'s `FACE_RECOGNITION`
        branch). Removing that button without another trigger would have silently stopped face
  grouping from ever running again. Fixed with a new `IPhotoApplication#bootstrapFaceRecognition`
        `CommandLineRunner` (idempotent, guarded by new `TaskRepository#existsByTaskType`),
  mirroring the existing `initDatabase`/`backfillGroupedDay` bean precedent there.
  - **Bug found in passing, superseded by the single-row correction above**: initially caught
  that `AdminService.updateImageTaskToDo()` (the other admin button, unrelated to this
  request but touched by it) replays every `canBeReplayed` task type *for every photo*
        missing one, and would have created one spurious, payload-less `ACTIVATE_HEAVY_TASKS` row
  per photo had the enum stayed `canBeReplayed=true` - patched with a one-line exclusion
  filter at the time. Once the user settled on `canBeReplayed=false` for the enum constant
  itself (the single-row design's natural consequence, not an independent fix), that filter
  became redundant and was removed again in favor of the simpler, single root cause.
  - **Frontend**: `UserManagement.jsx`'s old "Activate Face Recognition" button replaced with a
  checkbox (`heavyTasksActivated` state, fetched on mount from `GET /api/admin/heavyTasks`,
        `POST`s on change) - i18n keys `activateFaceRecognition`/`faceRecognitionActivatedSuccessfully`/
  etc. replaced with `heavyTasksActivationLabel`/`heavyTasksActivationUpdated`/etc. (en/fr).
  - **CPU-based light-pool concurrency** (user question: "is this even possible?"): yes, but
  not via `corePoolSize`/`maxPoolSize` the way the existing heavy-pool adjustment works -
        `lightThreadPoolExecutor` runs on virtual threads (`setVirtualThreads(true)`), which don't
  pool in the traditional sense, so those fields are essentially inert for it; and
        `queueCapacity` can't be resized after the executor's own initialization (Spring only reads
  it once, to size the underlying queue, at `afterPropertiesSet` time - confirmed, not just
  assumed). The real lever is how many new tasks `pollLightPendingTasks` pulls from the database
  each tick - new `TaskWorkerManager#lightConcurrencyLimit` (`AtomicInteger`, seeded from the
  executor's own `queueCapacity` as its ceiling), stepped ±10 each tick by
        `adjustLightConcurrencyForCpuLoad` based on `com.sun.management.OperatingSystemMXBean#
        getCpuLoad()` (0.0-1.0 recent system-wide load; despite the `com.sun` package name, this
  has been a stable, standard part of every HotSpot-derived JVM - Oracle, OpenJDK, Temurin -
  for years; returns `-1` only on its very first call or if genuinely unsupported, treated as
  a no-op). Below 50% load, steps the limit up (capped at `queueCapacity`); above 85%, steps
  it down (floored at 10); in between, leaves it alone to avoid thrashing on noisy samples.
  - Verified: whole backend `javac` compile clean (main + test sources), frontend
        `npm test -- --run` 101/101, `npm run build` clean, lint unchanged from its pre-existing
  baseline. **Not yet exercised against the live app** - needs a real restart (not IntelliJ
  HotSwap) like every other backend change this session, and the CPU-based scaling in
  particular has had no chance to be observed reacting to real machine load.

- [x] **Bug fix** (user report: "tasks are scheduled to run multiple times"). Root-caused by
  inspection, not guessed: `pollLightPendingTasks`/`pollFaceRecognitionTask` query for `status = PENDING`
      then `submit()` to an executor - but a task's status only flips to `PROCESSING` once
      `processTask` actually *starts running* on a worker thread (`markProcessing`), which lags
  behind submission whenever the executor is backed up (easy for the 3-thread heavy pool).
  In that window the row still looks `PENDING` in the DB, so the next poll tick's query
  matched it again and submitted it a SECOND time before the first submission had even run -
  the same task executing concurrently more than once.
  - **Fix**: new `TaskStatus.QUEUED` - both pollers now mark every fetched batch `QUEUED`
        (new `TaskWorkerManager#markQueued`, one transaction for the whole batch) BEFORE calling
        `submit()`, not after. `findExecutableTasks` still only matches `PENDING`, so a `QUEUED`
        row can never be re-selected while it's sitting in the executor's queue.
        `markProcessing` transitions `QUEUED → PROCESSING` exactly as before once the runnable
  actually starts - no change needed there.
  - **Two complementary recovery mechanisms, discussed with the user** (confirmed neither
  alone was enough): the existing hourly `reclaimStuckProcessingTasks` sweep widened to also
  reclaim stale `QUEUED` rows (new `TaskRepository#findByStatusInAndUpdatedAtBefore`,
  replacing the single-status version) - this stays the ongoing safety net for a task
  orphaned *without* a restart (e.g. `submit()` itself throwing after the DB was already
  flipped to `QUEUED`), where "stuck for over an hour" is the only available signal. New
        `IPhotoApplication#resetOrphanedTasksOnStartup` `CommandLineRunner` (new
        `TaskRepository#findByStatusIn`) unconditionally resets ANY `QUEUED`/`PROCESSING` row at
  boot, no staleness check needed - a fresh JVM means nothing in-memory survived the
  restart, so there's no "might still genuinely be running" ambiguity to wait out the way
  the hourly sweep has to. Without this second piece, every restart would leave whatever was
  in flight at that exact moment dead for up to an hour before the sweep's threshold caught
  up to it - a real (if minor) regression in restart recovery time, not just for this new
        `QUEUED` status but also, it turns out, for pre-existing `PROCESSING` orphaning that the
  hourly sweep alone already had this same gap for.
  - **Found and fixed in passing while auditing every other `TaskStatus` comparison in the
        codebase for `QUEUED`-awareness**: `ImmichImportService#isReclaimable` (the Immich-specific,
  15-minute-threshold reclaim used when a user manually retries a day's import) only checked
        `PROCESSING`, not `QUEUED` - `SYNC_FROM_IMMICH`/`UPLOAD_FROM_IMMICH` are light tasks too, so
  they can now land in `QUEUED` the same way. Widened to check both. Checked every other
        `TaskStatus` reference in the codebase: `UploadManager.jsx`'s upload-progress poller
  already treats any non-terminal, non-`PENDING` status as "leave the optimistically-set
        `PROCESSING` label alone" (it never special-cased `PROCESSING` explicitly either) - `QUEUED`
        falls into that same existing bucket for free, no frontend change needed.
  - Verified: whole backend `javac` compile clean (main + test sources), frontend
        `npm test -- --run` 101/101 (backend-only change, but confirmed nothing broke). **Not yet
        exercised against the live app** - needs a real restart, and the actual "does this stop
  duplicate execution" claim can only be confirmed by observing the heavy pool under a real
  backlog, which hasn't been done here.

- [x] **Bug fix** (user report: "if user move to garbage the suggested keep in duplicates, the
      duplicate popup disappear it should not"). Root cause was worse than the reported symptom -
      real data loss, not just the modal closing:
      - **`PhotoService.buildDedupedPage`/`PhotoMapper`**: a cluster's non-primary members exist
        ONLY nested inside the primary's own `duplicates` array - they're deliberately excluded
        from ever appearing as their own top-level page entries (`contentPhotos.removeAll
        (duplicatedPhotos)`). `utils/photoListUpdates.js#removeFromFlatPhotos` didn't know this -
        it just `filter()`ed out garbage'd ids, so garbage-ing the PRIMARY (the suggested keep)
        deleted its surviving duplicate members from the client's in-memory list entirely (not
        just from the modal - from the whole Library/day grid), since nothing else in the list
        ever referenced them. They'd only reappear on a full page refresh, once the backend
        naturally promoted a new primary on its own.
      - **Fix**: `removeFromFlatPhotos` now promotes the first surviving duplicate to take a
        removed primary's place (inheriting the rest as its own `duplicates`) instead of just
        dropping everything nested inside the removed primary. `removeFromPhotosByDay` gets this
        for free (delegates per-bucket). Rewrote `utils/photoListUpdates.test.js`'s fixtures to
        match the REAL single-top-level-entry-per-cluster shape (the old fixture had every member
        redundantly duplicated as its own top-level entry too, which isn't how the backend actually
        shapes this and would have hidden this exact bug from ever being caught by a test).
      - **`PhotoGrid.jsx`**: even with the data-loss fixed, the modal itself was still anchored by
        `openDuplicatePrimaryId` (the PRIMARY's id only) - after a promotion, that exact id is
        genuinely gone (the removed primary), so the lookup would still find nothing and wrongly
        close the modal even though the cluster survived under a new primary. Renamed to
        `openDuplicateMemberIds` (every member id the cluster had when opened) and match on ANY
        surviving member, not just the primary - correctly re-finds the cluster regardless of
        which specific member (including the primary) got removed. `DuplicateClusterModal.jsx`
        needed no change - it already renders a 1-member `item` gracefully (`canFlicker =
        members.length > 1` already false in that case), for the case where resolving the last
        actual duplicate leaves nothing left to compare.
      - **Tests**: new `PhotoGrid.test.jsx` case simulates the exact user-reported scenario
        (rerender with the post-garbage props `removeFromFlatPhotos` would actually produce) and
        asserts the modal stays open, now showing the promoted member. Plus new
        `photoListUpdates.test.js` cases for the promotion path itself, and for the cluster
        correctly disappearing entirely when the primary and all its remaining duplicates are
        removed together.
      - Verified: `npm test -- --run` 105/105, `npm run build` clean, lint unchanged from its
        pre-existing baseline. No backend change.

- [x] **Bug fix** (user report: `HttpMessageNotWritableException: No converter for [class
      java.util.HashMap] with preset Content-Type 'video/mp4'` when opening a video; user's own
      correct hunch: "related to the fact that I close lightbox without finishing to read the
      video"). The logged exception was a secondary, masking one, not the real cause:
      - **Actual mechanism**: closing the Lightbox mid-video aborts the browser's connection to
        `PhotoController#streamVideo`. Writing the in-flight response then throws a plain
        `IOException` once the connection is gone - but by that point the response is already
        COMMITTED (`Content-Type: video/mp4`, HTTP 206, partial bytes already streamed).
        `GlobalExceptionHandler`'s catch-all `@ExceptionHandler(Exception.class)` doesn't
        distinguish this from any other exception and tries to write its usual JSON error
        `HashMap` body anyway - Spring can't find a converter to write a `HashMap` as `video/mp4`,
        throws `HttpMessageNotWritableException`, and THAT'S what ends up in the logs, masking the
        real (and entirely benign) client-disconnect as the apparent error.
      - **Fix**: `handleAllExceptions` now takes an `HttpServletResponse` param and checks
        `response.isCommitted()` first - if already committed, nothing (headers or body) can be
        changed regardless of what's returned anyway, and the client is gone, so it just logs at
        `debug` and returns `null` (the standard way to tell Spring's
        `HttpEntityMethodProcessor` "already handled, don't write anything" from a
        `ResponseEntity`-typed `@ExceptionHandler` - it returns immediately for a null return
        value). Deliberately checks `isCommitted()` rather than pattern-matching specific
        exception types/messages (`ClientAbortException`, "Broken pipe", "Connection reset by
        peer", ...) - those vary by servlet container/OS and are fragile to match; "can this
        response still be written to at all" is the actually-correct, container-agnostic
        condition, and covers any other exception-after-commit scenario the same way, not just
        this one video-specific case.
      - Verified: whole backend `javac` compile clean (main + test sources). No frontend change.
        **Not yet exercised against the live app** - needs a real restart, and confirming the fix
        requires reproducing the exact repro (open a video, close the Lightbox before it finishes)
        against a running instance, which hasn't been done here.

- [x] **Refactor** (user request): replaced `SpaceService`'s in-JVM per-space lock
      (`AutoClosableLock`/`ReentrantLock` in a `ConcurrentHashMap`, `tryLock()` + `SpaceLockingException`
      + `@Retryable`) with an event-driven atomic count update. Every call site of the lock existed
      for exactly one reason - guarding `space.photoCount`'s Java-side read/increment/save against
      a lost update under concurrency - and nothing else, so removing the lock meant touching every
      one of those call sites (upload, Immich import, move to/from garbage, move to another space).
      - **`SpacePhotoCountChangedEvent`** (new, `org.iphoto.event`): a plain `record(spaceId, delta)`
        - not the `SpaceEntity` itself, so publishing never needs a managed instance.
        **`SpacePhotoCountListener`** (new, `@Component`): a plain `@EventListener` (deliberately
        NOT `@TransactionalEventListener` - this must fire SYNCHRONOUSLY, in the publisher's own
        active transaction, so the count change commits/rolls back atomically together with
        whatever photo add/remove triggered it; Spring invokes plain listeners immediately, in the
        publishing thread, which is exactly that) calling new
        **`SpaceRepository#adjustPhotoCount(spaceId, delta)`** - a single
        `UPDATE ... SET photo_count = photo_count + :delta WHERE id = :spaceId`.
      - **Why a plain atomic UPDATE instead of the compare-and-swap/optimistic-locking retry loop
        the user proposed** (raised and agreed before implementing): a pure delta increment doesn't
        need to read the current value first at all - the single UPDATE statement is already
        atomic and lost-update-free under concurrency (Postgres serializes concurrent writers to
        the same row for a plain UPDATE), so there's no read-then-write race window to guard
        against with a WHERE-guard-and-retry loop. That pattern earns its complexity when the new
        value depends on some in-memory computation that can't be expressed as a pure SQL
        expression (a real optimistic-locking use case) - not here, where it's always exactly
        `current ± 1`. Deliberately did NOT add `@Modifying(clearAutomatically = true)` either
        (unlike the similar existing `ImmichImportDayRepository#incrementImported` pattern this
        mirrors) - `moveAssetsToGarbage`/`restoreAssetsFromGarbage` fire this once per photo
        *inside a loop* over a `Set<PhotoEntity>` all loaded from ONE earlier query; clearing the
        whole persistence context on the first iteration would detach every other already-loaded
        photo in that same set, and calling `.getSpace()` (a LAZY relation - confirmed on
        `BaseEntity`) on any of them afterward would throw `LazyInitializationException`.
        Confirmed no caller actually re-reads a space's `photoCount` from the same managed entity
        within the same transaction after firing this event (grepped every `getPhotoCount()` call
        in the codebase) - the only reader is `SpaceService#mapSpaceEntityToSpaceDto`, always from
        a fresh, separate query in a different request/transaction, so there's no staleness gap to
        guard against either way.
      - **Call sites updated** (lock removed, direct `spaceService.increase/decreaseSpacePhotoCount`
        calls kept as-is - same public API, only what happens inside changed):
        `PhotoService#restoreAssetsFromGarbage`/`moveAssetsToGarbage`/`moveAssetsToMain`,
        `PhotoUploadService#processTheAsset` (also dropped its now-pointless
        `entityManager.refresh(space)` - that existed solely to avoid incrementing a stale
        in-memory count, moot once the increment itself is a pure atomic UPDATE that never reads
        the Java-side value at all; `EntityManager` field/import removed, now fully unused),
        `ImmichImportService#persistPhotoAndMetadata` (same `entityManager.refresh` removal, and
        updated its neighboring comment - it had specifically justified re-fetching `space`/`user`
        in the new transaction by needing "a genuinely managed entity" for the refresh call, which
        no longer exists; the OTHER reason to re-fetch, avoiding handing this transaction foreign
        persistence-context instances for `photoRepository.save(photo)`'s relationships, still
        holds and is what the comment says now). `PhotoUploadService#saveNewPendingTaskOnUploadingProcess`
        (the `@Recover` method for lock-exhausted retries) deleted outright - dead once
        `SpaceLockingException` can never be thrown. `TaskWorkerManager#processTask`'s
        `ServiceNotAvailableException | SpaceLockingException` catch narrowed to just the former.
        `@Retryable`/`@Backoff`/`@Recover` imports/annotations removed everywhere they were only
        ever used for `SpaceLockingException` (every occurrence in the codebase, confirmed by
        grep). `SpaceLockingException.java` itself deleted (no longer thrown anywhere).
      - **Bonus, not the original goal but a direct consequence**: `moveAssetsToMain`'s old nested
        locks (destination space, then per-photo source space) had a latent lock-ordering deadlock
        hazard if two concurrent moves ever ran in opposite directions between the same two spaces.
        Gone entirely now - each event targets one space by primary key, and Postgres row-level
        locking during a single-row UPDATE can't deadlock the way two independently-acquired
        in-JVM locks held across a whole block could.
      - Verified: whole backend `javac` compile clean (main + test sources; no test references to
        any of the removed lock/exception classes). **Not yet exercised against the live app** -
        needs a real restart, and confirming the actual concurrency property (two simultaneous
        uploads into the same space both correctly land on the right final count) requires load
        against a running instance with a real Postgres, which hasn't been done here.

- [x] **Bug fix (backend)**: Immich-imported albums never got any photos linked into them
      (`album_photos` had zero rows for every one of a user's 87 imported albums, confirmed via a
      direct DB query, not just the one album/photo originally reported). Root cause, **confirmed
      by the user against their own live Immich server**: `GET /albums/{id}` (`AlbumResponseDto`)
      does not carry an `assets` property on current Immich server versions - `ImmichClient
      .getAlbumDetail`'s `ImmichAlbumDetailDTO.assets()` always came back `null`, so
      `ImmichAlbumImportService#processOneAlbum`'s `linkable` list was always empty and
      `albumService.addPhotosToAlbum` was never even called - no exception, no warning, nothing
      visible to the user. (A same-shaped timing issue was also independently confirmed along the
      way and is worth knowing about even though it wasn't the actual root cause here: the specific
      photo the user first reported was imported 3 days *after* their one-time "Import Albums" run,
      and re-clicking "Import Albums" alone didn't fix it precisely because of the `assets` bug
      below, not because of timing - re-running "Import Albums" is still the right move any time
      more photos get synced after an album import, once this fix is in.)
      - **Fix**: album membership is now fetched the same way day/month asset sync already works
        for a date range (see `ImmichClient#getMonthBuckets`/`getAssetsForMonth`), just scoped by
        `albumId` instead of a date range: `ImmichClient#getAlbumTimeBuckets` calls
        `GET /timeline/buckets?albumId=...&size=MONTH` to list the album's buckets, then
        `ImmichClient#getAlbumBucketAssetIds` calls `GET /timeline/bucket?albumId=...&timeBucket=...`
        (singular - a distinct endpoint from the plural one above) per bucket to get the actual
        asset ids. That endpoint returns Immich's columnar response shape (one array per asset
        field, e.g. `id: [...]`, all parallel-indexed) rather than an array of asset objects - new
        `model/immich/ImmichTimeBucketAssetsDTO(List<String> id)` models just the column needed.
        `timezoneOffset` is fixed at UTC in `getAlbumTimeBuckets` rather than threaded through from
        the caller (unlike `getMonthBuckets`) - it only affects which month a near-midnight asset's
        bucket boundary falls into, and every returned bucket still gets fully enumerated either
        way, so the exact split doesn't change which assets are found.
      - `ImmichAlbumImportService#processOneAlbum` rewritten to collect asset ids across all of an
        album's buckets before the existing `existsById`/`linkable`/`skipped` logic (unchanged).
        Added `log.info`/`log.warn` at each step (buckets found, ids returned, linkable vs. skipped
        counts, confirmation `addPhotosToAlbum` ran) - added specifically because this bug produced
        zero visible signal anywhere (no exception, no warning in the returned summary DTO), so
        anything going wrong in this flow in the future should at least show up in the server log.
      - Dead code removed: `ImmichClient#getAlbumDetail` and `model/immich/ImmichAlbumDetailDTO`
        (the only thing `ImmichAlbumDetailDTO` had beyond `ImmichAlbumSummaryDTO` was the always-null
        `assets` field). `ImmichAssetRefDTO` kept - still used by `ImmichSharedLinkDTO`.
      - Verified: `javac` compile clean. **Not yet exercised against a live Immich server** - no
        live Immich access from this environment; needs the user to restart (this touches request
        bodies mid-loop, not safely HotSwappable) and re-click "Import Albums" against their real
        server to confirm `/timeline/buckets`/`/timeline/bucket` actually return what's expected
        here (both endpoints were modeled from documented shape + the user's own report, not a
        live call from this session).

- [x] **Refactor (backend + frontend)**: Immich album import made asynchronous, one background
      task per album - the fix above still ran every album's bucket/asset-id fetch inline on the
      triggering HTTP request, which for a user with dozens/hundreds of albums (many Immich round
      trips per album) could make "Import Albums" a very slow synchronous click.
      - New `TaskType.IMPORT_IMMICH_ALBUM(false, 2, false)` - light (not heavy), `canBeReplayed`
        false (not a per-photo task type, see its own enum comment). Reuses `TaskEntity.photoId`
        (a plain UUID column, no FK) to carry the *local* `AlbumEntity` id, the same "subject of
        this task" reuse pattern `PROCESS_UPLOAD`/`HASH_IMAGE` already use for photos - the Immich
        album id and album name travel in the JSON payload instead (needed for the Immich API
        calls; not persisted on `AlbumEntity` under that name).
      - `ImmichAlbumImportService#triggerAlbumImport` split in two: `queueAlbumImport` (still
        synchronous, called once per Immich album from the same per-album `TransactionTemplate`
        loop as before) now only finds-or-creates the local `AlbumEntity` row and queues one
        `IMPORT_IMMICH_ALBUM` task - skipping the queue if one is already `PENDING`/`QUEUED`/
        `PROCESSING` for that album (`TaskRepository#findByPhotoIdAndTaskTypeIn`, same idempotency
        guard `SYNC_FROM_IMMICH` already uses) so clicking "Import Albums" twice in a row doesn't
        race a still-running import; re-running after a task's already `COMPLETED`/`FAILED` is
        still allowed and expected (picks up new photos synced since, or retries a failure).
        `processAlbumImportTask` (new, called from `TaskWorkerManager`) is the actual bucket-
        fetch/link work, unchanged logic-wise from the previous fix, just moved out of the request
        thread. Shared-link processing is untouched and stays synchronous - it only ever needed
        the local album id (always available right after `queueAlbumImport`, regardless of whether
        that album's photo-linking task has run yet), never the linked photos themselves.
      - `TaskWorkerManager#processTask` gained an `IMPORT_IMMICH_ALBUM` branch calling
        `immichAlbumImportService.processAlbumImportTask(task)` then marking `COMPLETED` - already
        picked up by the existing light poller/CPU-based concurrency/stuck-task reclaim machinery,
        nothing task-type-specific needed there beyond the one new branch.
      - New `GET /api/immich/albums` (`ImmichAlbumImportService#listAlbumImportStatus`) - one row
        per Immich album this user has ever queued a task for (latest task per album, by `photoId`,
        since a retry/re-run creates a new task row rather than reusing the old one), combining
        that task's live `TaskStatus` with the current `AlbumEntity.photoCount` (read live, not
        cached in the task - so it keeps advancing as `processAlbumImportTask` actually links
        photos in, without needing a separate result-tracking mechanism). New
        `ImmichAlbumImportStatusDTO`/`ImmichAlbumImportTriggerResultDTO` (replaces the old
        `ImmichAlbumImportSummaryDTO`, deleted - its `photosLinked`/`photosSkippedNotYetImported`
        counts no longer exist synchronously; `POST /api/immich/albums/import` now only reports
        `albumsQueued`/`albumsAlreadyInFlight`).
      - Frontend (`hooks/useImmichImport.js`, `components/ImmichImportManagement.jsx`): mirrors
        the existing per-day polling shape (`fetchOverview`/`pollDay`) - `importAlbums()` now
        queues then starts `pollAlbums()`, a single `setInterval` re-fetching the *whole* album
        list every 3s (not one interval per album - dozens of albums is realistic, one shared
        poll is enough) until every row reaches `COMPLETED`/`FAILED`. Replaced the old one-shot
        `albumImportSummary` banner with a live-updating table (album name, linked photo count,
        status) fed by the new `albums` state, fetched on mount too (so reloading the page while
        an import is still running shows current progress, not nothing). i18n: removed
        `immichAlbumImportSummary`, added `immichAlbumImportTriggerResult`/`immichAlbumNameColumn`/
        `immichAlbumPhotosLinkedColumn`/`immichAlbumTaskStatus{InProgress,Completed,Failed}`/
        `failedToFetchImmichAlbums` (en+fr).
      - Verified: `javac` compile clean; `node --check` on the touched hook. No existing test
        coverage for `useImmichImport.js`/`ImmichImportManagement.jsx` (not in CLAUDE.md's
        regression-tested list) to run. **Not yet exercised against a live Immich server or a
        genuine app restart** - same caveat as the fix above, compounded here by also needing to
        confirm the background task actually gets picked up and completes end-to-end.

- [x] **UI (frontend)**: split `ImmichImportManagement.jsx` into two tabs ("Days"/"Albums",
      `immichDaysTabLabel`/`immichAlbumsTabLabel`) instead of stacking both sections on one page -
      the Days section (sync button, status filter, bulk-select, coverage banner, per-day table)
      and the Albums section (import button, trigger-result banner, per-album status table from
      the async-album-import work above) now render one at a time based on local `activeTab` state,
      settings section stays above both tabs since it's a prerequisite for either. Added
      `immichAlbumsNeedDaysFirst`/`immichNoAlbumsYet` empty-state copy for the Albums tab (no days
      imported yet / no albums imported yet, respectively). New `.immich-tabs`/`.immich-tab-button`
      rules in `UserManagement.css` (reused by this page already for its table/container styling) -
      no existing tab pattern elsewhere in the codebase to reuse instead.
      - Verified: `npx eslint` clean on both touched files.

- [x] **Test (backend)**: `src/test/java/org/iphoto/ImmichAlbumImportIntegrationTest.java` - a real
      Spring Boot + MockMvc + Postgres (Testcontainers) integration test for the whole async album-
      import feature above, same style as the existing `PhotoLifecycleIntegrationTest`. Mocks only
      `ImmichClient` (`@MockitoBean` - the one thing in this app that actually talks to a real
      Immich server; nothing in this environment can reach one) with a stubbed album containing one
      asset that exists locally and one that doesn't. Drives everything else through real HTTP:
      connect Immich settings, upload a real photo, trigger `/albums/import` (asserts it returns
      immediately with `albumsQueued=1`, and that an *immediate* second trigger correctly reports
      `albumsAlreadyInFlight=1, albumsQueued=0` - the in-flight dedup guard), force
      `taskWorkerManager.pollPendingTasks()` to run the queued `IMPORT_IMMICH_ALBUM` task, then
      asserts via `GET /api/immich/albums` AND a real `GET /api/albums/{id}/photos` call that
      exactly the one locally-existing photo got linked (not the never-imported one, not both).
      - Hoisted `loginAsAdmin`/`createSpace`/`uploadPhoto`/`waitForTaskCompletion`/`generateTestJpeg`
        out of `PhotoLifecycleIntegrationTest` into `AbstractIntegrationTest` as `protected` helpers
        (were private, duplicated verbatim) so both tests share them instead of a second copy.
      - Verified: `javac` compile clean (main + test sources together, `@MockitoBean` confirmed
        present in the resolved `spring-test-6.2.7.jar`). **Could not actually execute either test**
        in this sandbox - confirmed by also running the pre-existing, unmodified
        `PhotoLifecycleIntegrationTest` and hitting the identical failure: this environment's Docker
        Engine (29.5.3, API 1.54) rejects the old bundled `docker-java`/Testcontainers client's
        handshake outright ("client version 1.32 is too old, minimum supported is 1.40"), regardless
        of `DOCKER_API_VERSION`/`DOCKER_HOST` overrides - a Testcontainers-version-vs-Docker-Engine
        mismatch unrelated to this test's own code. (Found IntelliJ's bundled Maven at
        `/snap/intellij-idea-community/812/plugins/maven-plugin/lib/maven3/bin/mvn` and used it to
        confirm this, since no system `mvn`/`mvnw` exists otherwise.) User decided to leave the
        Testcontainers version as-is and run these tests in their own environment (there's already
        a GitHub Actions pipeline, per git log) rather than risk bumping a Spring-Boot-BOM-managed
        dependency version to work around a local sandbox limitation.

- [x] **Reorganize (backend)**: heavy-task concurrency reworked per explicit user request - one
  dedicated pool per heavy task type instead of one shared `heavyThreadPoolExecutor` (dynamically
  resized 3-8 threads based on light-pool load) that all three heavy task types competed for.
  - **Root cause of the "conflict" the user suspected**: each heavy service also held its own
  internal `ReentrantLock` capping ITSELF to exactly 1 concurrent run, regardless of how many
  threads the shared pool actually gave it - so increasing the shared pool's size never
  produced real parallelism for any single type, it just let more *different* types' tasks
  sit blocked on their own type's lock at once. **Found while fixing this**: `DuplicationService
        .checkDuplication`'s lock had a genuine bug - acquired via `tryLock()` then `lock()` again
  (a redundant re-entrant acquisition, hold count 2) but released only once in `finally`
        (hold count back to 1, never 0) - meaning after the very first successful call, the lock
  was permanently left held, and every later call (from a different pool thread) would find
        `tryLock()` false forever and immediately throw `ServiceNotAvailableException` - CHECK_DUPLICATION
  could never actually run a second time.
  - **New config** `IPhotoProperties.HeavyTasks` (`iphoto.heavy-tasks.*` in `application.yml`,
        `@ConfigurationProperties`): `duplication-threads` (default 1 - `DuplicationService` reads/
  writes overlapping `DuplicateEntity` groups non-atomically, unsafe concurrently with
  itself), `face-detection-threads` (default 3 - safe: each call opens its own DJL `Predictor`
        per invocation, `FaceDetectionService#detectAndSaveFaces` never shares one across calls),
        `face-recognition-threads` (default 1 - `groupFaces()` reprocesses/regroups ALL users'
  ungrouped faces in one run, unsafe concurrently with itself), `queue-capacity` (default 10,
  shared by all three).
  - `ThreadPoolConfiguration`: `heavyThreadPoolExecutor` bean replaced with three -
        `duplicationThreadPoolExecutor`/`faceDetectionThreadPoolExecutor`/
        `faceRecognitionThreadPoolExecutor` - each sized from the config above via a shared private
        `heavyThreadPoolExecutor(namePrefix, poolSize)` builder. All three keep the `HEAVY_TASK`
        thread-name prefix so `MainSpaceAspectConfiguration`'s thread-name check (which skips
  binding the per-request Hibernate space filter on background-task threads) still matches.
  - `TaskWorkerManager`: constructor now takes the three dedicated executors instead of one;
        `pollFaceRecognitionTask` polls and submits each task type against its own pool via a new
        `pollHeavyTaskType(TaskType, ThreadPoolTaskExecutor)` helper (reuses the existing
        `findExecutableTasks(status, now, Set<TaskType>, Limit)` query, `markQueued`, `processTask`
        unchanged) instead of one shared-pool poll over all three types combined. Removed the now
  entirely superseded `adjustHeavyPoolSizeForLightLoad`/`HEAVY_TASK_TYPES` (the shared pool's
  dynamic 3-8 resize heuristic doesn't apply to three separately-sized fixed pools). `shutdown()`
        now shuts down all three heavy pools instead of one.
  - Removed the now-redundant `ReentrantLock`s from `DuplicationService`/`FaceDetectionService`/
        `FaceRecognitionService` entirely - each dedicated pool's fixed size is now the only
  concurrency limiter, per the user's explicit "remove current lock" instruction (confirmed
  safe for face detection specifically: DJL's own per-call-Predictor pattern was already
  being used, the lock was only ever holding back parallelism there, not protecting anything).
        `ServiceNotAvailableException` (thrown only by these two locks' `tryLock()` failure paths,
  confirmed via grep) is now unreachable everywhere - deleted the class, and the now-dead
        `catch (ServiceNotAvailableException e)` branch in `TaskWorkerManager#processTask`.
  - Verified: whole backend `javac` compile clean (main + test sources). **Not yet exercised
        against the live app** - needs a real restart (new constructor params/bean names aren't
  HotSwappable), and confirming the actual concurrency property (3 ISOLATE_FACES tasks
  genuinely running at once, CHECK_DUPLICATION surviving past its first run) requires
  watching real task rows against a running instance, which hasn't been done here.

- [x] **Bug fix / optimization (backend)**: "huge CPU consumption" reported right after the
      heavy-task-pool rework above - root-caused as PyTorch native-thread oversubscription, a
      regression directly caused by that same change, not a new/separate issue.
      - **Root cause**: `IPhotoApplication.main` set `ai.djl.pytorch.num_threads=8` unconditionally
        (PyTorch's CPU backend otherwise defaults to using every core for the tensor math inside a
        single inference call - see that line's own pre-existing comment, which already explained
        this). That flat "8" was only ever safe because ISOLATE_FACES could previously only run 1
        concurrent `Predictor` call at a time (the old lock, since removed). Once
        `face-detection-threads` (default 3) allowed genuinely concurrent `detectAndSaveFaces`
        calls, EACH one independently claims 8 native threads - up to 3 * 8 = 24 threads
        contending for however many cores the machine actually has, which thrashes (more total CPU
        burned on contention/context-switching, not more real work done) rather than scaling.
      - **Fix**: `IPhotoApplication.main` now registers an `ApplicationEnvironmentPreparedEvent`
        listener (fires once `application.yml` is bound into the `Environment` but before any
        `@Bean` - including `FaceDetectionConfiguration`'s DJL model beans - is created, which is
        the exact window needed: the property must be read before the PyTorch engine initializes,
        but reading it requires Spring's environment to already be prepared) that computes
        `ai.djl.pytorch.num_threads = max(1, availableProcessors / face-detection-threads)` instead
        of a flat 8 - so the total native thread budget stays proportional to the machine's real
        core count no matter how `iphoto.heavy-tasks.face-detection-threads` is tuned.
      - **Also found and fixed while investigating** (`FaceRecognitionService`, the other CPU-heavy
        path): `findBestMatchForFace`'s per-face loop re-decoded and re-normalized the SAME face's
        own embedding from a fresh `byteArrayToFloatArray` call for every candidate person (up to
        `persons.size()` times per face, each also redundantly recomputing that same vector's norm
        inside `cosineSimilarity`) - pure wasted CPU, since that vector never changes within one
        face's matching pass. New `NormalizedEmbedding` record (vector + precomputed norm) decoded
        once per face instead, threaded through `findBestMatchForFace`/`scoreAgainstPerson`/
        `calculateAverageSimilarity`/a new `cosineSimilarity(NormalizedEmbedding, float[])`
        overload. Also replaced `Math.pow(x, 2)` with a plain `x * x` in both `cosineSimilarity`
        overloads (transcendental-function overhead for what's always squaring - a well-known
        Java micro-optimization, correct regardless of the rest of this fix).
      - **Investigated and ruled out as a concern**: `DuplicationService`'s comparisons are cheap
        Hamming distance over `phash`/`mhash` byte hashes computed once at upload time (`HASH_IMAGE`
        task), not recomputed per comparison - not a CPU hotspot, left untouched.
      - **Not pursued further, flagged as a possible future follow-up, not done here**: the
        (face, person) matching loop still issues 2 `faceRepository` queries per pair
        (`nearestTemporalNeighbors`) - for many faces against many persons this is a large number
        of small DB round-trips, a separate (I/O-heavier, not CPU-heavy the way the above was)
        potential cost worth revisiting if face recognition is still slow/heavy after this fix.
      - Verified: whole backend `javac` compile clean (main + test sources). **Not yet exercised
        against the live app** - needs a real restart; confirming this actually resolved the CPU
        usage requires watching real CPU load during a face-detection burst on a running instance
        with several `ISOLATE_FACES` tasks in flight at once, which hasn't been done here.

- [x] **Optimization (backend)**: face recognition matching moved to a precomputed/persisted
      structure, per the user's explicit request - the same "prepare data and store in the
      database at write time, cheap read at match time" pattern `DuplicationService` already uses
      for `phash`/`mhash`, applied here to the (face, person) matching loop flagged as a follow-up
      in the previous optimization entry (2 `faceRepository` queries per pair).
      - New `PersonEmbeddingBucketEntity` (`person_embedding_buckets` table, unique on
        `(person_id, bucket_year)`): a running-average embedding per person, per calendar year of
        their photos. Bucketed by year (not one average across a person's whole history)
        specifically to preserve the property the old per-comparison temporal-neighbor window
        existed for - a person's appearance can change a lot across years, so blending every era
        into one global average would dilute the match signal (see `FaceRecognitionService`'s
        `THRESHOLD` comment). Pure derived/cache data - cascades away entirely
        (`cascade=ALL, orphanRemoval=true` on new `PersonEntity#embeddingBuckets`, plus
        `@OnDelete(CASCADE)` at the DB level, same double pattern `FaceLinkExclusionEntity` already
        uses) when its person is deleted, unlike `FaceEntity` which deliberately survives with its
        person reference just nulled.
      - `PersonRecognitionService` gained `addFaceToBucket`/`removeFaceFromBucket` (incremental
        weighted-average add/remove of one face's embedding into/out of its person's matching-year
        bucket) and `mergeBuckets` (folds a merge's source buckets into the target's matching-year
        buckets directly, bucket-by-bucket, instead of re-decoding every individual face's
        embedding again). Wired into **every** point a face is attached to or detached from a
        person, audited across the whole codebase: `updatePerson`/`addPerson` (auto-recognition
        match / new-person clustering), `mergeFacesIntoTarget` (the `/persons/merge` and rename-
        collision-merge flows), and `FaceService`'s `attachFaceToPerson`/`detachFaceFromPerson`
        (rename/unlink/set-face-person flows) and `deleteFace`. Also wired into
        `FaceDetectionService#deleteFaces` for consistency, even though that method turned out to
        be dead code already (confirmed via grep - no caller anywhere; permanent photo deletion
        actually goes through `PhotoEntity.faces`' own JPA cascade instead, `PhotoDeletionService`
        never calls this method) - **not fixed here**, out of scope, but flagged: permanently
        deleting a photo with a recognized face today updates neither cover-face state (a
        pre-existing gap) nor these new buckets, since nothing hooks into that cascade path.
      - `FaceRecognitionService#groupFacesForUser` batch-loads every candidate person's buckets
        once per run (`personEmbeddingBucketRepository.findByPersonIdIn`, one query total) instead
        of any per-(face,person)-pair query. `scoreAgainstPerson` compares a face against a
        person's `NEAREST_BUCKET_COUNT` (2) nearest-by-year buckets, weighted by each bucket's
        `faceCount` so a thin bucket can't outweigh a well-populated one - the year-bucket
        equivalent of the old before/after face-level `TEMPORAL_NEIGHBOR_WINDOW` (kept, but now
        only used as `clusterByTemporalProximity`'s NEW-person-clustering cost cap - unrelated to
        this change). Removed `nearestTemporalNeighbors`/the old per-person-faces
        `calculateAverageSimilarity` overload entirely, along with the two now-fully-unused
        `FaceRepository` derived queries they were the only caller of
        (`findByPersonAndPhotoDateTakenBeforeOrderByPhotoDateTakenDesc`/`...AfterOrderBy...Asc`).
      - New shared `util/EmbeddingCodec` (`toBytes`/`toFloats`, BIG_ENDIAN float32) - extracted
        since `PersonRecognitionService` now also needs to encode/decode embeddings for bucket
        maintenance; previously duplicated separately in `FaceDetectionService` (encode-only) and
        `FaceRecognitionService` (decode-only).
      - **Known, accepted limitation, not addressed here**: incremental running-average
        add/remove accumulates floating-point drift over many cycles (same tradeoff any streaming-
        average algorithm accepts) - not expected to matter at realistic per-bucket face counts for
        a personal photo library, no periodic reconciliation job built.
      - Verified: whole backend `javac` compile clean (main + test sources). **Not yet exercised
        against the live app** - needs a real restart (new table via `ddl-auto: update`, new
        constructor deps); confirming this actually reduced face-recognition run time/DB load, and
        that matching quality wasn't degraded by the year-bucket granularity, requires watching a
        real `groupFaces()` run against a real, populated `people` table, which hasn't been done
        here.

- [x] **Bug fix (backend)**: CPU usage was still "really high" after the PyTorch `num_threads`
      fix above - root-caused live, not guessed, by inspecting the actual running process in this
      sandbox (found via `ps aux`, PID discoverable since the app happens to run in the same
      environment this session has shell access to): `jcmd <pid> Thread.print` confirmed the
      Java-level heavy-task pools were correctly sized (3 face-detection + 1 face-recognition + 1
      duplication, exactly as configured, and `jcmd <pid> VM.system_properties` confirmed
      `ai.djl.pytorch.num_threads=5`, the expected `16 cores / 3` from the previous fix) - so the
      *Java* thread story was already correct. The actual problem was one level lower: comparing
      `jcmd`'s thread list against a raw `/proc/<pid>/task` OS-thread listing showed **far more**
      OS-level threads sharing each pool thread's name (truncated to 15 chars, e.g.
      `heavy-task-dupl`) than Java threads existed - 46 OS threads under the single
      `heavy-task-duplication-1` thread's name (should be 1), 16 under the 4 face-detection/
      recognition threads (should be 4). Held steady over a 20s re-check (not an active leak, a
      one-time-oversized allocation) - narrowed to OpenCV's own internal `parallel_for_` backend
      (`DuplicationService`'s `ORB`/`BFMatcher`/`AverageHash`/`PHash` calls, via
      `org.bytedeco.opencv`): the pthread-only backend this prebuilt javacpp binary uses (no TBB)
      defaults each distinct OpenCV subsystem to lazily spinning up its own hardware-concurrency-
      sized worker pool rather than sharing one process-wide pool - three subsystems (ORB,
      BFMatcher, img_hash) × ~16 cores ≈ the observed 46.
      - **Fix**: `IPhotoApplication.main` now calls `org.bytedeco.opencv.global.opencv_core
        .setNumThreads(1)` before Spring even starts (native library loads on first reference, so
        as early as possible, same reasoning as the PyTorch property) - `CHECK_DUPLICATION`'s
        concurrency is already fully governed by its dedicated single-thread pool
        (`IPhotoProperties.HeavyTasks#duplicationThreads`), a single photo-sized image comparison
        doesn't need OpenCV's own internal multi-threading on top of that, so disabling it
        outright is simpler and safer than trying to correctly size three separate per-subsystem
        pools.
      - **Also set defensively** (found extra native threads under the face-detection/recognition
        pool threads too, beyond what `num_threads` alone accounts for):
        `ai.djl.pytorch.num_interop_threads=1` - a separate PyTorch pool from `num_threads`
        (intra-op), otherwise left at whatever LibTorch defaults to. Each detect/embed call here is
        one sequential single-image inference, no multi-graph work that would need inter-op
        parallelism.
      - Verified: `javac` compile clean; `org.bytedeco.opencv.global.opencv_core.setNumThreads`
        confirmed present via `javap` against the resolved jar. **Not yet re-verified against the
        live process** - the live thread-dump investigation above was against the OLD build (pre-
        this-fix); needs a genuine restart and a fresh `/proc/<pid>/task` count to confirm the
        OS-level thread count actually drops to match the Java-level pool sizes now.

      **Follow-up, confirmed live after restart**: the `opencv_core.setNumThreads(1)` fix worked -
      duplication's OS thread count dropped from 46 to exactly 1. The `num_interop_threads=1` fix
      for the face side did NOT eliminate its extra native threads (still ~16 OS threads under the
      4 real Java heavy-task-face threads, stable, not growing) - but those extras carry real CPU
      load roughly matching the "real" threads', and 3 concurrent detections * num_threads=5 ≈ 15,
      close to this machine's 16 cores - closer to correctly-saturated parallelism than
      oversubscription now, not clearly a bug. Left as a tunable via
      `iphoto.heavy-tasks.face-detection-threads` (lower it if the machine needs to stay
      responsive for other things while a face-detection backlog runs) rather than chased further.

- [x] **Bug fix (backend) - real native memory leak, found live**: user reported CPU still high
      after the fixes above AND suspected "GC failing to free memory" - confirmed via the live
      process: `VmRSS` 1.96GB but `VmSwap` 4.39GB (most of this process's actual memory footprint
      was sitting in swap, not resident), while JVM heap (`jcmd GC.heap_info`) looked completely
      normal (3.6/4GB, unremarkable for an active app). This is the classic JavaCPP/OpenCV
      off-heap-leak signature: a `Mat`'s native pixel buffer is invisible to heap-occupancy-based
      GC pressure (the Java-side wrapper object is tiny regardless of how large the buffer behind
      it is), so the JVM has no reason to collect eagerly no matter how much native memory piles
      up - only an explicit `Mat.release()` reliably frees it promptly.
      - **Root cause**: `DuplicationService#preprocess` (runs once per uploaded photo, the
        HASH_IMAGE task) held `image`/`rotatedImage`/`resizedImage` - by far the largest Mat in
        this whole file, a full (or MAX_SIZE-resized) decoded photo - and only released it on the
        very last, un-thrown line of the method. Any exception thrown by `pHash.compute`/
        `averageHash.compute`/`computeAndStoreOrbFeatures` (entirely plausible across a large,
        diverse real photo library - unusual color spaces, corrupt files, formats OpenCV handles
        oddly) skipped that cleanup entirely, permanently leaking that photo's full pixel buffer -
        every time it happened, with no crash, no visible symptom besides slowly climbing native/
        swap memory. `image.empty()`'s early-return path had the same gap (leaked on every
        unreadable file). The rest of the file (`confirmWithFeatureMatching`/
        `countHomographyInliers`, the ORB-verification path) was already correctly wrapped in
        try/finally - `preprocess` was the one significant gap, and also the hottest/highest-
        volume path (every photo, not just duplicate-candidate comparisons).
      - **Fix**: wrapped `preprocess`'s body in try/finally releasing `image` (the reassignment
        chain already released each Mat it replaced at the moment of reassignment, so by the time
        the finally runs, `image` is the only one that could still be outstanding, regardless of
        how many rotate/resize reassignments happened) - exception or not, the buffer is now
        always freed. Also fixed the `image.empty()` early-return leak, and tightened
        `computeAndStoreOrbFeatures`'s `descriptors`/`mask` Mats into their own inner try/finally
        (a smaller, secondary gap - post-resize descriptor buffers are much smaller than the full
        image, found while already in this method for the main fix).
      - Verified: `javac` compile clean. **Not yet re-verified against the live app** - needs a
        restart, then watching `VmRSS`/`VmSwap` (or overall swap usage) over a real processing
        backlog to confirm memory actually stays bounded now instead of climbing.

      **Follow-up, investigated live after user reported the leak still felt present**: audited
      every other `org.bytedeco`/OpenCV usage site in the codebase (`ThumbnailService`,
      `PhotoUploadService`) - both already correctly wrap `FFmpegFrameGrabber`/`Java2DFrameConverter`
      in try-with-resources, no gap found there; `DuplicationService#preprocess` above was the one
      real off-heap gap. Re-checked the live process (restarted, ~11 minutes of real usage):
      `jcmd GC.heap_info` showed heap at 96% of the 4GB max, `jcmd GC.class_histogram` showed
      `byte[]` (140,000 instances, ~1.38GB) overwhelmingly dominant - almost certainly
      `FaceEntity.embedding`/`faceThumbnail` and `PersonEmbeddingBucketEntity.embedding` payloads.
      **Forced a full GC (`jcmd GC.run`) and heap usage dropped from 3.5GB to 1.57GB** - this is
      the key distinguishing test: a true unreachable-object leak would NOT shrink after a forced
      full collection, so this is not one. Root cause instead: `FaceRecognitionService.groupFaces`/
      `groupFacesForUser` run in one Hibernate session (class-level `@Transactional`), and every
      `PersonEntity`/`FaceEntity`/`PhotoEntity`/`PersonEmbeddingBucketEntity` touched while
      processing a user - up to `MAX_UNGROUPED_FACES_PER_RUN` faces, every existing person, all
      their buckets, and (via `person.getFaces()` inside `recalculateCoverFace`) every face already
      linked to any person just matched - stays pinned in that one persistence context for the
      whole run, even after `entityManager.flush()` (flush only writes changes out, it doesn't
      detach anything). Reruns every ~30s, so with a large real library this legitimately built up
      a big momentary working set faster than G1 felt pressured to collect on its own.
      - **Fix**: `groupFaces` now calls `entityManager.clear()` (not just `flush()`) after each
        user finishes, detaching everything accumulated for that user once it's durably committed.
        Restructured to fetch user **ids** only up front and re-look-up each `UserEntity` fresh
        inside the loop, specifically so `clear()` mid-loop can't detach a not-yet-processed user
        still sitting in a pre-fetched list (a real, easy-to-miss gotcha with this exact "clear()
        inside a loop over an eagerly-fetched list" pattern - detaching an entity you haven't used
        yet risks a `LazyInitializationException` the moment anything touches an uninitialized lazy
        field on it later).
      - **Also, system-wide context worth being honest about**: this sandbox runs the app
        (`-Xmx4G`), a full IntelliJ + attached debugger, and Postgres all on one ~7.8GB-RAM box -
        `free -h` showed the system itself under real memory pressure (6.4GB swap in use)
        independent of any one process's behavior. Not something a code fix addresses; worth
        keeping in mind before attributing every high-memory reading here to app-level bugs.
      - Verified: `javac` compile clean. **Not yet re-verified against the live app** - needs a
        restart; confirming this actually reduces peak heap during a real `groupFaces()` run
        requires watching `jcmd GC.heap_info`/class histogram again afterward, which hasn't been
        done against this specific change yet.
      - **Follow-up, confirmed live**: after adding `MALLOC_ARENA_MAX=2` to the process
        environment (see `Dockerfile`, `ENV MALLOC_ARENA_MAX=2`), the JVM-heap-vs-OS-process gap
        that was the real remaining "big memory pressure" culprit shrank from ~4.3GB unaccounted
        native memory down to ~1.3GB, confirmed via the same `smaps_rollup`/`GC.heap_info` method:
        before, `pmap -x` showed ~36 anonymous mappings sized almost exactly 64MB each (~2.3GB) -
        the textbook signature of glibc giving each native-calling thread (face detection/
        recognition/duplication, all doing JNI calls) its own malloc arena, growing in large
        chunks rarely returned to the OS even after the memory inside them is freed. After the fix,
        that 64MB-mapping bucket disappeared entirely from the histogram. Must be set in the
        process's own environment before the JVM/glibc start (glibc reads it once via `getenv()`
        at init) - can't be applied from Java code, hence the Dockerfile `ENV` and (for local/
        IntelliJ runs) the run configuration's own environment variables.
- [x] **Model swap**: replaced RetinaFace (detection) + a generic `face_feature` model (embedding) -
      both pulled at build time from an unversioned DJL "test-models" demo bucket with no real
      production distribution guarantee - with InsightFace's `buffalo_l` pack: **SCRFD**
      (`det_10g.onnx`) for detection, **ArcFace R50** (`w600k_r50.onnx`) for embedding, the same
      pack Immich uses for the same self-hosted-photo-app use case. Both ship ONNX-only, so this is
      an engine swap too (PyTorch -> ONNX Runtime, `ai.djl.onnxruntime:onnxruntime-engine`), not
      just a model file swap; the two `.onnx` files are vendored directly under
      `src/main/resources/models/` (committed, not downloaded at build time) - the actual fix for
      the "no production guarantee" problem, not just a different download URL.
      1. New `configuration/ScrfdDetectionTranslator.java` replaces `FaceDetectionTranslator`
         (deleted) - SCRFD's decode is genuinely different math from RetinaFace's variance-scaled
         prior-box offsets (distance-based per-anchor box/landmark decode instead), ported from
         InsightFace's own reference implementation. Preserves the exact output contract
         `FaceDetectionService`/`FaceAligner` depend on: a `Rectangle` normalized to [0,1] fractions
         of the original image, but `Landmark` keypoints in ABSOLUTE pixel coordinates (an
         easy-to-miss asymmetry in the existing contract - `FaceDetectionService` multiplies bounds
         by width/height, `FaceAligner` uses landmark points directly with no further scaling) and
         the same 5-point ordering (left eye, right eye, nose, left mouth, right mouth) RetinaFace
         used - `FaceAligner` needed zero changes.
      2. **Real bug caught before it ever ran against production data**: DJL's default
         `Batchifier` assumes a model's output tensors carry a leading batch dimension and tries to
         split it accordingly on unbatch; `det_10g.onnx`'s outputs don't (flat per-anchor tensors,
         no batch axis) - this threw immediately on a real prediction ("split_size can only be 0 if
         dimension size is 0, but got dimension size of 3200", 3200 being one stride's anchor
         count, not a batch size). Fixed by disabling batching entirely
         (`getBatchifier() { return null; }`, matching this translator only ever handling one image
         per call anyway) and adding the batch dimension manually in `processInput`
         (`array.expandDims(0)`) since the model's INPUT still needs one even though its outputs
         don't.
      3. **Verified empirically against real photos** (per the migration plan's explicit call for
         this, since there's no unit test for this decode math, same as was true of RetinaFace's):
         ran detection + alignment + embedding end-to-end against two real multi-face photos from
         this account's own library, annotated boxes/landmarks onto the image and visually
         inspected them. All correctly-oriented faces (6/8 and 4/5 respectively - the misses in
         both cases were people upside-down/sideways, e.g. a handstand, which RetinaFace couldn't
         detect either, not a regression) got tight boxes with landmark points landing precisely on
         eyes/nose/mouth, across varying scale, angle, glasses, and partial occlusion. Embeddings
         came back 512-dim as expected for ArcFace R50.
      4. `FaceDetectionConfiguration`: both `Criteria` beans repointed at the vendored `.onnx`
         files, `.optEngine("OnnxRuntime")`. ONNX Runtime's own thread tuning
         (`optOption("interOpNumThreads"/"intraOpNumThreads", ...)`) now lives here as plain
         per-model options, replacing the old PyTorch-only `ApplicationListener` hack in
         `IPhotoApplication` (had to run before Spring context refresh, since PyTorch's thread
         config was JVM-wide system properties read at native-engine-init time) - removed, along
         with the `ai.djl.pytorch:*` Maven dependencies (confirmed via grep nothing else in the
         codebase used the PyTorch engine).
      5. **No data migration** (per explicit direction: existing `faces`/`people`/
         `person_embedding_buckets` rows are meaningless against the new embedding space anyway, so
         they're simply truncated rather than migrated) - **still to do before this is live**:
         truncate those tables, re-enqueue `ISOLATE_FACES` for every existing photo, and re-tune
         `FaceRecognitionService.THRESHOLD` (currently still 0.60, tuned for the OLD model) against
         real match/no-match examples once faces are re-detected/re-grouped on the new model -
         ArcFace's cosine-similarity distribution commonly sits differently and this was never
         re-validated.
      - Verified: `mvn clean compile` green with PyTorch fully removed from the classpath; model
        loading and detection/embedding inference manually smoke-tested end-to-end as described
        above. **Not yet wired into a live end-to-end run of the actual app** (table truncation +
        re-enqueue + threshold re-tuning above still pending).
      - **Follow-up, real bug caught by the user hitting it live**: the smoke-testing above had a
        gap - it ran while `ai.djl.pytorch:*` was still on the classpath (removed in a later
        cleanup step), and DJL silently used PyTorch's fully-featured NDArray implementation for
        `ctx.getNDManager()`'s general arithmetic (transpose/flip/sub/div/expandDims) even though
        `.optEngine("OnnxRuntime")` only controls which engine runs the model itself. Once PyTorch
        was actually removed and the app run for real,
        `ScrfdDetectionTranslator.processInput` threw `UnsupportedOperationException: This NDArray
        implementation does not currently support this operation` immediately - ONNX Runtime's own
        NDArray (`OrtNDArray`) only implements basic tensor creation/read, not general arithmetic.
        DJL's built-in `ImageFeatureExtractorFactory` (used for the embedding model) does its
        ToTensor/Normalize preprocessing via the exact same kind of NDArray ops, so it was equally
        broken, just not yet reached (detection throws first).
        - **Fix**: rewrote both translators to build their input tensor via plain Java pixel loops
          (`BufferedImage.getRGB` bulk read into a `float[]`, manual CHW/channel-order/normalize
          math) with only a single `NDManager.create(float[], Shape)` call at the end - a basic
          construction op every DJL engine must support - instead of any NDArray arithmetic.
          `processOutput` already only used `.toFloatArray()` (a basic read op), so it needed no
          change. New `configuration/ArcFaceEmbeddingTranslator.java` replaces
          `ImageFeatureExtractorFactory` for the embedding model for the same reason.
        - **Bonus catch, not just a fix**: `ImageFeatureExtractorFactory`'s `ToTensor` never swaps
          channel order (stays RGB), so the embedding model had actually been fed RGB when
          InsightFace's own reference `arcface_onnx.py` preprocessing (like SCRFD's) uses BGR
          (`swapRB=True`) - the hand-written replacement uses the correct BGR order, so this also
          fixes a real, would-have-shipped channel-order mismatch, not merely restoring the
          previous (already slightly wrong) behavior.
        - **Re-verified against the TRUE classpath** (rebuilt via `mvn dependency:build-classpath`
          after the PyTorch removal, confirmed zero `pytorch` jars, zero `PtEngine` log lines) -
          both models load and run end-to-end, same two real photos as before: detection
          boxes/landmarks came back bit-for-bit identical to the earlier (PyTorch-assisted) run,
          confirming the plain-Java rewrite is a faithful equivalent, not just "no longer crashes".

- [x] **Interface + Impl split for every `service/` class.** Every Spring-managed service bean
      (30 classes across `service/`, `service/auth/`, `service/immich/`) now has a public interface
      exposing just its public methods, with the concrete class renamed to `<Name>Impl` implementing
      it (e.g. `PhotoService`/`PhotoServiceImpl`, `FaceDetectionService`/`FaceDetectionServiceImpl`).
      `UserDetailsServiceImpl` was left alone - it already implements Spring Security's own
      `UserDetailsService` interface, so it already followed this exact pattern for a
      framework-owned interface.
      - **Why the interface keeps the original class name** (not a `I`-prefixed name, nor the impl
        keeping the bare name): every existing `@Autowired`/constructor-injected field, controller,
        and test across the whole codebase types its dependency as e.g. `PhotoService` - keeping
        that name on the *interface* and moving the implementation to a new `PhotoServiceImpl` name
        meant zero call sites anywhere else in the app needed to change. Verified up front (grep)
        that nothing does `new XxxService(...)` directly (all are Spring beans, wired only via
        autowiring), so this rename is safe everywhere.
      - **Nested types stay on the interface, not the impl** - `TaskMapper.Action` (enum),
        `UserService.ImmichCredentials`/`DeviceTokenService.IssuedToken`/
        `ImmichCredentialCipher.Encrypted` (records) are all referenced externally as
        `OuterName.NestedType` (e.g. `TaskMapper.Action.ON_CREATE_PHOTO_ACTION` in `PhotoService`,
        `import org.iphoto.service.UserService.ImmichCredentials` in `ImmichClient`). Since nested
        types declared directly inside an interface are implicitly `public static`, moving them
        into the interface (not the impl) keeps every existing qualified reference compiling
        unchanged.
      - **`ShareService.computeUserMd5` stays a `static` method on the interface itself** (with a
        body - interfaces have supported static methods since Java 8), for the same reason: it's
        called elsewhere as `ShareService.computeUserMd5(...)` (see `ImmichAlbumImportService`), and
        a static method can't be "implemented" by a bean anyway.
      - **Package-private helper methods promoted to public on the interface** where another class
        in the same package called them without qualification - `PersonRecognitionService`'s
        `recalculateCoverFace`/`addFaceToBucket`/`removeFaceFromBucket` were package-private,
        callable from `FaceDetectionService`/`FaceService` only because they're in the same
        package; a Java interface can't declare a package-private method, so these had to become
        part of the public interface contract.
      - **`@Transactional`/`@Scheduled`/`@PreDestroy` annotations stayed on the impl classes**, not
        moved to the interface - Spring Boot defaults `spring.aop.proxy-target-class=true` (no
        override found in `application.yml`), so AOP proxies are CGLIB subclasses of the concrete
        class, and method-level annotations are read off the target class either way. This would
        matter (annotations would need to move to the interface) only under a JDK dynamic proxy,
        which this app doesn't use.
      - **No other file changed** - every controller, other service, and the two integration tests
        (`AbstractIntegrationTest`/`ImmichAlbumImportIntegrationTest`, which `@Autowired protected
        TaskWorkerManager taskWorkerManager` and call its now-still-public `pollPendingTasks()`)
        kept compiling with zero edits, confirming the naming choice above actually delivered on
        "proxy to the final implementation" with no ripple effect elsewhere.
      - Verified: `mvn compile` and `mvn test-compile` both green.

- [x] **Per-interface test suite added, switched from Testcontainers Postgres to in-memory H2.**
      Every interface from the split above got its own test class (25 total: `TaskService`,
      `DayIndexCache`, `RequiresNewTransactionService`, `MenuService`, `PhotoStoragePathResolver`,
      `PhotoDeletionService`, `TaskMapper`, `ImmichCredentialCipher`, `TaskManagerService`,
      `DeviceTokenService`, `StorageMigrationService`, `AdminService`, `SpaceService`,
      `DuplicationUniquenessService`, `ThumbnailService`, `ShareService`, `UserService`,
      `PersonRecognitionService`, `FaceService`, `AlbumService`, `EditPhotoService`, `PhotoService`,
      `PhotoUploadService`, `TaskWorkerManager`), covering every public method - except
      `FaceRecognitionService`/`DuplicationService`/`FaceDetectionService` (excluded per explicit
      request) and `ImmichClient`/`ImmichAlbumImportService`/`ImmichImportService` (excluded too -
      the old `ImmichAlbumImportIntegrationTest` was removed outright rather than replaced, per the
      same request). Mostly driven through real controller endpoints (MockMvc), falling back to
      directly autowiring the interface only for methods with no HTTP endpoint of their own.
      - **H2 instead of Testcontainers**: `AbstractIntegrationTest` no longer starts a real Postgres
        container - `com.h2database:h2` (test-scope) replaces `spring-boot-testcontainers`/
        `testcontainers-junit-jupiter`/`testcontainers-postgresql` in `pom.xml`, and the datasource
        is now `jdbc:h2:mem:iphototest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1` with the Hibernate dialect
        overridden to `H2Dialect` (PostgreSQLDialect assumes a real Postgres server on the wire).
        MODE=PostgreSQL is what lets the app's own Postgres-flavored native query (`ORDER BY
        random() LIMIT`, in `PhotoRepository.findRandomPhotosBySpaceIdAndGroupedDayBetween`) and
        `BYTEA` columnDefinitions keep working unmodified. One known, accepted gap: H2 can't parse
        the `enum(...)` DDL Hibernate/H2Dialect generates for `ImmichImportDayEntity.status` under
        this mode (logged as a schema-creation warning, doesn't fail the build) - harmless here
        since Immich is out of scope for this suite, not chased further.
      - **Same fixed H2 URL across every test class** means Spring reuses one cached application
        context (and its H2 database) for the whole run, same as the previous Testcontainers setup
        already did with a static `@Container` field on the shared base class - test isolation
        comes from unique per-test names/ids, not a fresh database per class.
      - **`AbstractIntegrationTest#runAsAdmin`** (new helper): a handful of methods with no HTTP
        endpoint route through a `@MainSpaceLimited`-annotated repository query anyway (see
        `MainSpaceAspectConfiguration`), which reads the current user off `SecurityContextHolder` -
        populated by the security filter chain around a real request, but empty in a bare JUnit
        thread calling a service directly. `runAsAdmin` pushes a real "admin" `Authentication` for
        the duration of a `Callable`, then restores whatever was there before.
      - **`generateTestJpeg()` fixed to embed per-call randomness** (a random fill color) instead of
        producing byte-identical output every call - `PhotoRepository.findByFileHash` enforces
        file-hash uniqueness *globally* (a deliberate dedup feature), and with every test class now
        sharing one database across the whole run, a fixed image made every upload after the first
        one in the entire suite collide as a content-duplicate (task stuck at IGNORED, never
        COMPLETED) - reproduced live while writing `AlbumServiceTest`.
      - **Three real, pre-existing bugs found and fixed** while writing these tests (not worked
        around):
        1. `SpaceService.createSpace` had no pre-save name-collision check (unlike
           `AlbumService.createAlbum`/`ShareService.createShare`, which do) - a duplicate space name
           fell through to the `name_user_unique` DB constraint and 500'd instead of returning 409.
           Added `SpaceRepository.findByNameAndUser` and the same pre-check the sibling services use.
        2. `PersonRecognitionServiceImpl` was class-level `@Transactional(propagation =
           REQUIRES_NEW)` - fine for `mergePersons`/`mergeInto` (which already explicitly override
           back to plain `@Transactional`), but wrong for `addFaceToBucket`/`removeFaceFromBucket`/
           `recalculateCoverFace`/`updatePerson`/`addPerson`: a caller that saves a brand-new
           `PersonEntity` and then calls one of these in the *same*, not-yet-committed transaction
           (e.g. `FaceServiceImpl.setFacePerson`'s new-person branch) got a foreign key violation -
           the suspended, uncommitted `PersonEntity` insert is invisible to the independent
           REQUIRES_NEW transaction trying to insert a `person_embedding_buckets` row referencing
           it. Changed the class-level annotation to plain `@Transactional` (REQUIRED).
        3. `FaceController.getPersonFaces`'s `@PageableDefault` sorted by `"dateTaken"` - a property
           that doesn't exist on `FaceEntity` at all (only on `PhotoEntity`, via its `photo`
           association) - so the endpoint 500'd on its own default pagination whenever a caller
           didn't override the sort. Fixed to `"photo.dateTaken"` (a valid nested property path).
      - Verified: full suite green, 101 tests, run twice in a row to check for order-dependent
        flakiness from the shared database (found and fixed one such case: `AdminServiceTest`
        assumed `ACTIVATE_HEAVY_TASKS` started deactivated, which broke once another test class -
        `TaskWorkerManagerTest` - had already activated it earlier in the same run; now establishes
        its own starting state explicitly rather than assuming a global default).

- [x] **Fix: backend tests couldn't run in CI against the real face-detection ONNX models.**
      `src/main/resources/models/{det_10g,w600k_r50}.onnx` are git-lfs tracked (`.gitattributes`),
      but `.github/workflows/docker-image.yml`'s `actions/checkout@v4` doesn't fetch LFS content by
      default - in CI those two files are just ~130-byte pointer text, not real ONNX, so
      `FaceDetectionConfiguration`'s `Criteria.loadModel()` calls (which run eagerly at context
      startup as plain `@Bean` methods, autowired or not) failed fast the moment `mvn test` spun up
      any `@SpringBootTest`. Fetching real LFS content in CI wasn't a good fix either (167MB pulled
      on every run against GitHub's small free LFS bandwidth quota) - and unnecessary, since no test
      in the suite exercises real face detection anyway (faces are inserted directly via
      `FaceRepository`, see `FaceServiceTest`'s own comment).
      - `FaceDetectionConfiguration` (both `@Bean` methods) and `FaceDetectionServiceImpl` (the only
        consumer of either `ZooModel` bean) gained `@Profile("!test")` - `FaceDetectionServiceImpl`
        already had it from an earlier, incomplete pass, but nothing ever activated the "test"
        profile, so it was a no-op; `FaceDetectionConfiguration` itself had no annotation at all, so
        the actual model-loading beans were still created regardless.
      - `AbstractIntegrationTest`'s `@SpringBootTest` now sets `spring.profiles.active=test`.
      - New `TestFaceDetectionService` (`src/test/java/org/iphoto/service/`, `@Profile("test")`) - a
        no-op stand-in, needed because `TaskWorkerManagerImpl` has a mandatory constructor
        dependency on `FaceDetectionService` regardless of whether anything in the test suite
        actually calls it. Its `detectAndSaveFaces` still sets `TaskStatus.COMPLETED` itself,
        matching the real impl's contract - `TaskWorkerManagerImpl.processTask`'s `ISOLATE_FACES`
        branch relies on the callee for that (unlike most other task types), so leaving it unset
        would strand a task in `PROCESSING` forever if one ever gets dispatched against this stub.
      - Verified: full suite green (103 tests) with the two `.onnx` files temporarily moved out of
        `src/main/resources/models/` entirely, simulating exactly what CI actually sees today.
