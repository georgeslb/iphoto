# Refactor TODO

Running list of in-progress/planned refactor work on this app (frontend at `static/src`, backend
at `src/main/java/org/iphoto`). Update this file whenever new work is identified or completed —
add finished items to the compressed history in §2 (a line or two, not a session narrative) and
keep §1 to genuinely outstanding work, so nothing gets lost between sessions but the file stays
readable. See `specification.md` for current app *behavior* — this file is for work still to do
and a compressed record of how we got here, not a second copy of the spec.

## 1. Open / Not Yet Done

### Bugs
- **`GET /api/faces/persons` has no working pagination on the frontend.** It's a real
  `Page<PersonDTO>` (`FaceController#getPersons`, default page size 50), but
  `hooks/usePersons.js` fetches with no `page`/`size` params and no load-more mechanism — anyone
  with more than 50 people can never see the rest. Needs `usePersons.js` to track page/hasMore
  state and append pages (infinite-scroll, consistent with `PhotoGrid.jsx`'s day-indexed grid, is
  probably the better fit than classic page controls for this photo-grid-styled page).
- **Mobile/Android photo grid shows a variable column count** (commonly 5) instead of a fixed 4 —
  both use "auto-fill by minimum tile size" grids. Fix: web — `App.css`'s `.photo-grid` under the
  `≤768px`/`≤480px` media queries, change `grid-template-columns: repeat(auto-fill, minmax(...))`
  to `repeat(4, 1fr)` (leave `.album-grid` alone). Android — `PhotoGridContent.kt`'s
  `GridCells.Adaptive(minSize = 100.dp)` → `GridCells.Fixed(4)`. Pure layout change, not verified
  on a real device/emulator.
- **Flaky backend test**: `PhotoServiceTest.downloadSingleAndMultiplePhotosSetContentDispositionAndZipEntries`
  fails intermittently in a full `mvn test` run (wrong zip entry count) but passes reliably in
  isolation. `AbstractIntegrationTest#generateTestJpeg()`'s per-call random solid-color fill has a
  real, higher-than-expected MD5 collision rate (JPEG quantization likely maps many solid colors
  to identical bytes), which can make `PhotoRepository.findByFileHash`'s global uniqueness treat a
  fresh upload as a duplicate — but that alone would surface as a `waitForTaskCompletion` timeout,
  not this test's own "zip size" assertion, so there's likely a second, still-unidentified
  mechanism (possibly a stale/reused `photoId` from a collision elsewhere in the same suite run
  interacting with this test's fixtures). Not yet root-caused — needs a repeated full-suite run
  with debug logging around `findOwnedByIds`/the zip-writing loop in `PhotoController#downloadMultiple`.

### Features
- **Reject blurry faces at detection time** (confirmed 2026-09-09 — user: "we have too many
  [faces] right now"). Technique: variance of the Laplacian (grayscale the face crop, run a
  Laplacian edge filter, take the variance — low variance = blurry), via OpenCV's
  `Imgproc.Laplacian`/`Core.meanStdDev` (already a dependency, no new library needed). Threshold
  needs empirical tuning against this app's real photos (start near the commonly-cited ~100
  cutoff, verify against real sharp/blurry crops before trusting it). Hooks into
  `FaceDetectionService.detectAndSaveFaces`, same place the `MIN_FACE_SIZE`/
  `SMALL_FACE_MIN_CONFIDENCE` checks already reject detections — a blurry crop simply never
  becomes a `FaceEntity`, no new column/UI needed. Explicitly out of scope for this pass: cleaning
  up already-saved blurry `FaceEntity`/`PersonEntity` rows from before this ships (destructive,
  needs its own explicit go-ahead later if the Persons-page clutter is still bad afterward).
- **Face-position overlay on a person's own photo grid** (`PersonDetail.jsx` → `PhotoGrid`):
  reuse the Lightbox's `.face-box` overlay style, scoped to just the viewed person's face per
  photo. Needs backend support first — neither `Photo` nor `getPersonPhotos` currently carries
  that person's face x/y/width/height for a given photo; cleanest fix is extending
  `PersonController`'s day-photos response to include it server-side rather than stitching two
  separately-paginated endpoints together client-side. Frontend needs the same
  original-resolution-to-thumbnail-size scaling math `Lightbox.jsx`'s `recalculateImageSize`
  already does, reusable if extracted.
- **Persons should sort named people before unnamed, then by photo count descending** (user
  request, 2026-09-09). `FaceController#getPersons`'s current `@PageableDefault(sort="name")`
  can't express "empty/null last" or sort by photo count — needs a real query with an explicit
  `ORDER BY CASE WHEN name IS NULL OR name = '' THEN 1 ELSE 0 END, photoCount DESC`, applied
  server-side unconditionally (not overridable via `?sort=`). Check first whether `PersonEntity`
  actually has a persisted/queryable `photoCount` column to sort by — if not, that's the real
  blocker (would need one maintained incrementally, the way `AlbumEntity`/`SpaceEntity.photoCount`
  already are, not computed per-request). Do not fake this with client-side re-sorting of one
  already-paginated page.
- **Back button on the Person detail page** (`/persons/:personId` → `/persons`). Copy the
  existing `Header.jsx` pattern used for Albums/Spaces (`pathname.startsWith('/albums/') &&
  menuAlbumId` → back button) — check how that's actually threaded in before assuming the same
  mechanism transfers as-is for Persons. New `backToPersons` i18n key.
- **Search/sort input for the Albums grid** (`/albums`). `GET /api/albums` already returns the
  full unpaginated list, so this is a client-side filter, no backend change — case-insensitive
  substring match against `album.name`, probably also worth sorting the filtered result
  alphabetically (check `AlbumServiceImpl.getAllAlbums`'s current order first). User asked for a
  polished UI element (styled like `.modal-input`, a `lucide-react` `Search` icon), not a bare
  `<input>`. No debounce needed for an in-memory filter, but check `albums.length` is small enough
  for a plain `useMemo` re-filter per keystroke to stay smooth.
- **Duplicate-cluster badge: icon + count only on mobile/Android**, no "similar" text label —
  follows from the fixed-4-column mobile grid above needing more room. Web: split the combined
  `similarPhotosBadge` i18n template so the count can render standalone and the label can be
  CSS-hidden at the mobile breakpoint (same pattern as `.lightbox-action-label`) — do not delete
  the combined key, `DuplicateClusterModal.jsx`'s own title badge still uses it and should keep
  the full text. Android: no duplicate badge exists in the native app yet at all — just a design
  note for whenever that's built (icon + count from day one, no label).

### Known gaps / cleanup
- **`App.jsx` selection state**: `selectedIds`/`selectedAlbumIds`/`selectedSpaceIds` and their
  setters are still threaded through as many individual props rather than one consolidated
  object. Deliberately deferred — touches the prop signature of most consumer components
  (`Header`, grids, modals) — worth a dedicated pass if there's appetite.
- **Large files not yet reviewed for internal duplication**: `Lightbox.jsx` (~520 lines),
  `UploadManager.jsx`, `useAlbumManagement.js` (largest hook — remember Album/Space are NOT
  interchangeable domain concepts; any shared logic factored out must stay presentation-only).
- **Unused npm packages**: `react-advanced-cropper`, `react-photo-album` — confirmed unused
  anywhere in `src/`, candidates for `npm uninstall` absent a near-term plan to use them.
- **Dead state, `Lightbox.jsx`**: `albumAllowed`/`setAlbumAllowed` — set but never read. Worth
  checking git history before deleting in case it's a half-finished feature rather than leftover.
- **Dead CSS, `App.css`**: `.library-timeline`/`.memory-card` and a first, duplicate `.memories-strip`
  rule (around line 54-67) look like leftovers from the old `LibraryTimeline.jsx`/month-timeline
  design that was fully replaced by the day-indexed Memories bar (a second, real `.memories-strip`
  block exists further down and is what `MemoriesBar.jsx` actually uses) — confirm nothing still
  references the first block before deleting it.
- **`react-hooks/exhaustive-deps` warnings not yet individually triaged** (each needs judgment,
  not a blind auto-fix — some omissions are intentional): `useMenuSelection.js` (missing
  `setPhotoSelection`), `App.jsx` (missing `currentAlbumId`/`currentSpaceId`/`selectedMenu`),
  `UploadManager.jsx` (unnecessary `effectivePath`/`refreshAndMaintainScroll`),
  `useAlbumManagement.js` (unnecessary `fetchAlbums`, x2), `UserManagement.jsx` (missing
  `fetchUsers`), `Lightbox.jsx` (missing `handleNext`/`handlePrevious`).
- **No CI workflow runs `npm test`/`npm run lint`/`mvn test` automatically** — both suites are
  currently local-only (`.github/workflows/docker-image.yml` only builds/pushes the image). Worth
  wiring up if regressions matter enough to gate merges on.
- **Regression tests worth adding, not yet written**: `UploadManager.jsx`'s "PENDING keeps
  polling" fix (needs fake timers + mocking the chunk-upload flow); a root `App.jsx` smoke test
  (would need mocking every on-mount endpoint: `/api/user/me`, `/api/photos`,
  `/api/photos/selected`, `/api/albums`, `/api/spaces`).
- **CI build-time cache duplication**: the `test` job and `build-and-push` job each independently
  resolve the full (large, native-binary-heavy) Maven dependency tree via two cache namespaces
  that don't share bytes — every `pom.xml` change re-pulls the same artifacts twice. A real fix
  (e.g. a BuildKit `--mount=type=cache` sharing an `actions/cache`-backed `~/.m2` into the Docker
  build) is a non-trivial change to the build stage, not attempted without the ability to verify a
  real `docker build` end-to-end.

## 2. Done — compressed history

The sections below summarize completed work by area, newest within each area last. Full
rationale/investigation detail for any of these has been trimmed — check git blame/history on
this file (or the relevant code's own comments) if the "why" behind a specific decision ever
matters again; `specification.md` is the source of truth for current behavior.

### Photo grid, selection & browsing
- Day-indexed browsing replaced fixed-size-page pagination everywhere (Library/Album/Space/
  Garbage/Person): a derived `groupedDay` column on `PhotoEntity`, per-day count+cover queries
  (later split into a plain count query + a separate random-cover query, then the cover dropped
  entirely once it turned out unused), `hooks/useDayIndexedPhotos.js` on the frontend, one shared
  `IntersectionObserver` per grid. Replaced the older month-timeline/cursor system entirely
  (`jumpToMonth`/`TimelineMonthDTO`/`LibraryTimeline.jsx`, all deleted). A day's per-request count
  query is cached (`DayIndexCache`, evicted on any mutation that could change a day's bucket) for
  Library/Space/Album; Garbage/Person are deliberately not cached (low traffic).
- **Memories bar** (Library only): a horizontal strip of random photos per configured "N months
  ago" offset (`iphoto.memories.offsets`, supports a `±Nd` day-tolerance window per offset),
  auto-cycling `photosPerBlock` photos per offset. Clicking a memory scrolls to its day in the
  grid (via the same `seekToDay` mechanism the timeline scrubber uses) rather than opening the
  Lightbox.
- **Timeline scrubber**: a right-edge vertical strip (one label per year) that jumps instantly
  (no smooth-scroll, so nothing in between gets fetched) to any day, loaded or not, via
  `PhotoGrid.jsx`'s per-day `data-day-group` markers.
- **Shift+click range selection**, drag-to-reorder photos within a day (re-stamps `dateTaken`,
  reusing the existing single-photo date PATCH endpoint per moved photo — required widening that
  endpoint's date parser from minute to full ISO precision), bulk "Edit Date Taken" and bulk
  "Download" (zip, streamed, ownership-filtered) actions — all implemented once in
  `PhotoGrid.jsx`/`usePhotoActions.js` and shared across every grid view.
- **Inline duplicate-cluster grouping** in the grid evolved through several iterations (collapse/
  expand in place → a dedicated `DuplicateClusterModal.jsx` popup showing full-resolution
  originals side-by-side with a flicker/toggle compare, a metadata diff, and per-member trash/
  unlink actions) — see the Duplicate Detection section below for the backend side. Garbage-ing a
  cluster's primary photo now correctly promotes a surviving member rather than losing it or
  closing the modal (a real bug: the old code only ever filtered the flat photo list, unaware that
  non-primary members exist solely nested inside the primary's own `duplicates` array).
- Responsive/mobile support added from a zero-`@media`-query baseline: collapsible sidebar drawer,
  a uniform square-cropped Android/iOS-style grid on ≤768px/≤480px, a stacked (not side-by-side)
  Lightbox layout, icon-only Lightbox action buttons on mobile, horizontally-scrolling header
  actions. Not exhaustively verified on a real device — touch-target sizing and exact sticky-
  header offsets are best-estimate.
- Fixed: scroll position not restored after closing the Lightbox (a hardcoded `null` instead of
  the real `saveScrollPosition` in a hook call); a Lightbox close on the same page silently reset
  the grid to page 0, dropping already-loaded pages; upload status stuck permanently on "Pending"
  (the poller treated it as terminal); face bounding-box overlays drawn offset from the real face
  (missing `position: relative` on the overlay's parent, plus un-applied `object-fit: contain`
  letterbox-gap math already solved elsewhere in the same file for pan-clamping).

### Duplicate detection
- Two-tier detection: a tight global pHash/averageHash Hamming-distance match confirms directly;
  a looser "plausible" band falls back to ORB keypoint matching + RANSAC homography (with both an
  absolute inlier-count *and* an inlier-*ratio* floor — the ratio exists because RANSAC can find a
  large consensus set even among unrelated photos sharing repetitive texture like grass or wood
  grain, which produced a real false-positive between two unrelated photos before the ratio floor
  was added). ORB descriptors/keypoints are computed once at upload time and persisted
  (`PhotoEntity.orbDescriptors`/`orbKeypoints`), not recomputed per comparison.
- Fixed a self-contradictory group-join condition that made every detected duplicate spawn a new,
  overlapping group instead of ever merging into an existing one; grouping now treats clusters as
  connected components and merges every touched group into one.
- `CHECK_DUPLICATION` runs on its own 30s poll, split off from `FACE_RECOGNITION`'s 5-minute one
  (see Backend infrastructure below) since duplicate detection is fast and a user is often
  actively waiting on it right after an upload.

### Persons & faces
- See `specification.md` §4.3 for the current design (model, small-face gating, clustering). Below
  is what changed to get there:
  - Model swap: RetinaFace+PyTorch → InsightFace `buffalo_l` (SCRFD detection + ArcFace R50
    embedding) via ONNX Runtime — both `.onnx` files vendored in-repo instead of downloaded at
    build time. Existing `faces`/`people`/embedding-bucket rows were truncated, not migrated (a
    different model means a different, incompatible embedding space).
  - Small-face handling: found and fixed a real `FaceAligner` bug (both eye landmarks read off a
    freshly-created iterator, so eye-line rotation was a permanent no-op for every face) before
    tuning the small-face size/confidence gate — the bug, not an inherent limit of small crops,
    was the main reason including them previously produced bad embeddings.
  - Clustering redesigned twice: first to a temporal-window + union-find scheme so a childhood and
    adult photo of the same person could chain together without loosening the match threshold;
    then, after a real production OOM, to the current bounded-representative-cap
    (`CandidateClusterer`) design that processes a user's backlog in chronological slices instead
    of holding an unbounded connected-component in memory. Existing-person matching moved from
    scanning nearby individual faces to precomputed per-person-per-year embedding buckets
    (`PersonEmbeddingBucketEntity`), maintained incrementally.
  - Fixed a real production bug where face grouping pooled every user's faces/persons together
    globally instead of per-user: `groupFacesForUser` ran on a thread name
    `MainSpaceAspectConfiguration` treats as exempt from the `@MainSpaceLimited` space filter, so
    its queries were silently unfiltered. Fixed by resolving the target space explicitly and
    querying by it directly, not relying on the annotation/`SecurityContextHolder` at all.
  - Feature set: Person detail page (`/persons/:personId`, mirrors Album/Space detail) with a
    horizontal person-switcher strip; manually remove one face detection; named face labels;
    rename/reassign a single face's person from the Lightbox (with a merge-confirmation prompt on
    a name collision); unlink a face from its person (or a person from one whole photo) without
    deleting the face; merge two people (direction fixed: fewer faces merges into more); whole-
    person rename with its own, separate collision/merge picker. A `face_link_exclusions` table
    remembers manual unlinks so the ~30s auto-regrouping pass can't silently reattach a face to a
    person the user explicitly removed it from (not recorded for an explicit person-merge, which
    is a deliberate "same person" declaration rather than a correction).
  - Bug fixes along the way: cover-face selection crashing the whole `/persons` list when one
    person had none; cover face never getting reassigned after its photo was deleted; `PersonEntity`
    missing the `isNew()` JPA callbacks every sibling entity has (caused
    `detached entity passed to persist` on cross-transaction saves); `/api/faces/photo/{id}` not
    scoped to the requesting user's own space (security fix).
  - **Merge suggestions**: a new extra step (`PersonMergeSuggestionService`), run right after each
    user's own face-to-person matching/clustering inside `groupFacesForUser`, compares every
    existing person's overall (all-years-averaged) embedding directly against every other
    person's — much stricter threshold than ordinary face-to-person matching — and upserts a
    `PersonMergeSuggestionEntity` row for any pair similar enough to plausibly be the same real
    person (dropping a row that no longer clears the bar, e.g. after an unlink). Never merges
    anything itself. Library page: `PersonMergeSuggestionBanner.jsx` shows a notice whenever
    `GET /api/faces/persons/merge-suggestions` returns at least one suggestion; its popup
    (`PersonMergeSuggestionModal.jsx`) shows one pair at a time with Merge (the existing
    `/persons/merge` two-person flow, unchanged), Next, and Close. A merge discards any suggestion
    naming either person (the deleted source via FK cascade, the surviving target explicitly,
    since its embedding just changed) so the next run recomputes it fresh.

### Sharing
- Full design in `specification.md` §4.4. Originally broken end-to-end (wrong URI parsing, guest
  sessions hitting `@MainSpaceLimited` lookups that 500, no session ever established for
  unprotected links, a frontend/backend field-name mismatch, expiry never actually checked) —
  rewritten rather than patched: `/share/{userMd5}/{name}` addressing, a `ROLE_SHARE` guest
  session with its own `ShareAccessFilter` authorization boundary, guest-safe unfiltered lookups
  kept separate from the authenticated app's space-filtered ones, and a guard against clobbering
  an owner's real session when previewing their own link.

### Uploads
- Fixed: a client-rejected (wrong type) file losing its Retry button; a `FAILED` task with no
  message showing neither error text nor Retry (backend now falls back to the exception's class
  name when `getMessage()` is null); "Save as New" during image editing being permanently dead
  code (read a `FormData` field the wrong way).
- Feature: clicking a completed (or in-progress-but-safe) upload's file name opens a small,
  transparent-overlay preview popup without navigating away — iterated from an earlier scroll-
  and-highlight approach that was replaced per user feedback.
- Image editor migrated twice: `tui-image-editor` (large, unmaintained, imperative-DOM) →
  `react-easy-crop` (crop/rotate/zoom only) → `react-cropper` (real draggable/resizable crop box,
  what "desktop crop tool" behavior actually needed) — exports always at full source resolution
  via canvas, not the on-screen preview size.

### Immich import
- Moved from an admin-only, env-var-configured feature to per-user self-service (`UserEntity`
  stores an encrypted per-user Immich URL/API key; the encryption key is derived from the user's
  own password hash — an explicit obfuscation-at-rest tradeoff, not a real secret, chosen so
  background import tasks with no HTTP session can still decrypt).
- Day-index sync had to move from Immich's (removed) day-bucket API to fetching month buckets and
  grouping assets locally by taken-date, since current Immich server versions only support
  month-sized timeline buckets.
- Per-asset import is two chained, independently-committed tasks (`SYNC_FROM_IMMICH` stages the
  file, `UPLOAD_FROM_IMMICH` commits the DB rows and only *then* moves the file to its final
  path) specifically so a crash mid-import can never leave an orphaned file blocking every future
  retry the way a single-phase design did.
- Album import required its own bucket-based asset-listing fix (an album detail response Immich no
  longer returns `assets` on), then was made asynchronous (one background task per album) so
  importing dozens/hundreds of albums doesn't block the triggering request; a bulk-import (up to
  30 days at once) and an "IN_PROGRESS"/orphaned-task reclaim mechanism were added afterward.
- Several rounds of task-reliability hardening: reclaiming tasks orphaned in `PROCESSING` (both an
  Immich-specific 15-minute reclaim and a general hourly sweep across every task type), retrying
  DB/network errors with a delay instead of failing permanently, and closing a double-counting
  race in the per-day import-progress counters.

### Backend task/concurrency infrastructure
- Heavy (CPU-bound) task types (duplicate detection, face detection/recognition) were split off
  the general-purpose virtual-thread executor into their own bounded, platform-thread pools —
  first one shared pool, then (after finding each service's own internal lock capped it to 1
  concurrent run regardless of pool size, defeating the point) one dedicated pool per task type,
  sized via `iphoto.heavy-tasks.*`. Light tasks are separately capped (`Semaphore`, currently 40
  concurrent) to avoid exhausting the DB connection pool during a burst.
- A `QUEUED` status was added between `PENDING` and `PROCESSING` after finding tasks could be
  double-submitted in the window between a poll tick fetching a task and it actually starting.
- Space photo counts moved from a per-space in-JVM lock to an event-driven atomic
  `UPDATE ... SET photo_count = photo_count + :delta` — simpler and race-free without needing a
  read-modify-write retry loop, since the update is always a pure delta.
- Root-caused and fixed several native-resource issues under real production load: PyTorch (then
  ONNX Runtime)/OpenCV each independently oversubscribing native threads once heavy tasks ran with
  real parallelism; an off-heap `Mat` leak in duplicate-detection preprocessing whenever an
  exception skipped its release; a large per-user Hibernate persistence context pinning heap
  during face-recognition runs; glibc's per-thread malloc arenas inflating native RSS (fixed via
  `MALLOC_ARENA_MAX=2` in the container environment, since glibc only reads it at process init).
- Backend service layer refactored into interface + `*Impl` pairs for every `service/` class, and
  the test suite moved from Testcontainers-Postgres to an in-memory H2 database (`MODE=PostgreSQL`)
  shared across one test run for speed — found and fixed a few real bugs in the process (missing
  space-collision check on space creation, a transaction-propagation bug in person/bucket
  maintenance, an invalid default sort property).
- Schema management moved from Hibernate `ddl-auto: update` to Flyway-versioned migrations
  (`ddl-auto: validate` now only checks the schema matches, never changes it).

### Admin & misc backend
- `AdminController` moved under the `/api` prefix (it was the one controller not following that
  convention, which broke it under the Vite dev proxy) with a matching `SecurityConfig` update to
  avoid a privilege-escalation regression.
- Replaced the one-shot "Activate Face Recognition" admin button with a persistent on/off switch
  for heavy task processing generally, backed by a single control task row.
- Fixed a `RootController` SPA-fallback regression (`@RestController` serialized the literal
  `"forward:/index.html"` string into the response body instead of resolving it as a real forward)
  and a Leaflet marker-icon/Vite bundling issue that showed broken-image icons on the Map view.
- Photo storage reorganized from one flat directory into `{year}/{month}/` subfolders (by
  `dateTaken`); an optional admin-triggered backfill migrates pre-existing flat-stored files.
