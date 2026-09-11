# Refactor TODO

Running list of in-progress/planned refactor work on this app (frontend at `static/src`, backend
at `src/main/java/org/iphoto`). Update this file whenever new work is identified or completed —
add finished items to the compressed history in §2 (a line or two, not a session narrative) and
keep §1 to genuinely outstanding work, so nothing gets lost between sessions but the file stays
readable. See `specification.md` for current app *behavior* — this file is for work still to do
and a compressed record of how we got here, not a second copy of the spec.

## 1. Open / Not Yet Done

### Bugs
- **Merging persons from the merge-suggestion review flow still not working** (reported
  2026-09-11). `PersonMergeSuggestionBanner.jsx`/`PersonMergeSuggestionModal.jsx`'s Merge button is
  supposed to reuse the existing `/persons/merge` two-person flow unchanged (see "Persons & faces"
  history below), but the user reports it's not working — not yet reproduced/root-caused. Check
  whether this is a regression in the suggestion popup's call path specifically (vs. the underlying
  `PersonRecognitionServiceImpl.mergeFacesIntoTarget` merge itself, which has its own passing
  regression test) — start by reproducing from the Library banner and checking the browser network
  tab/backend logs for the actual request/response on click.

### Features
- **Face-position overlay on a person's own photo grid** (`PersonDetail.jsx` → `PhotoGrid`):
  reuse the Lightbox's `.face-box` overlay style, scoped to just the viewed person's face per
  photo. Needs backend support first — neither `Photo` nor `getPersonPhotos` currently carries
  that person's face x/y/width/height for a given photo; cleanest fix is extending
  `PersonController`'s day-photos response to include it server-side rather than stitching two
  separately-paginated endpoints together client-side. Frontend needs the same
  original-resolution-to-thumbnail-size scaling math `Lightbox.jsx`'s `recalculateImageSize`
  already does, reusable if extracted.
- **Duplicate-cluster badge on Android**: the native app has no duplicate-cluster UI at all yet
  (`PhotoGridContent.kt`/`PhotoThumbnail.kt` just render plain thumbnails) — a design note for
  whenever that's built: icon + count only from day one, no "similar" text label, matching the
  web version's mobile styling (see compressed history below).

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
- **No CI workflow runs `npm test`/`npm run lint` automatically** — the frontend suite is still
  local-only. (`mvn test` now runs on every PR into master via
  `.github/workflows/docker-image.yml`'s `test` job, and is required to pass before the Docker
  image job — which itself is now `workflow_dispatch`-only, not automatic on push to master.) Worth
  wiring up the frontend suite too if regressions matter enough to gate merges on it as well.
  Reminder: once this workflow exists on master, its `test` job still needs to be added as a
  required status check under Settings > Branches for master (branch protection isn't something a
  workflow file alone can set) for PRs to actually be blocked on failure.
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
- Fixed a real bug (reported 2026-09-11): opening the Lightbox left `MemoriesBar` mounted
  underneath it (only visually covered, App.jsx's `libraryPhotoGrid` doesn't gate it on
  `isLightboxOpen` the way it does `PhotoGrid`), and its own `MemoryItem` auto-cycle `setInterval`
  kept running regardless - every ~3s it advanced to a different, unrelated photo and fetched that
  photo's thumbnail, showing up in the Network tab as thumbnail requests for photos that have
  nothing to do with the asset actually open in the Lightbox. Fixed by threading `isLightboxOpen`
  down to `MemoryItem` and skipping the interval entirely while it's true (`MemoriesBar.test.jsx`).
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
- **Day-count look-ahead prefetch** (user request, 2026-09-11 — "I would like to fetch day, when
  user reach the last assets two previous days", i.e. not a bug, a UX/perf ask): the day-sentinel
  `IntersectionObserver` in `PhotoGrid.jsx` used to rely purely on a generous pixel `rootMargin`
  (1500px) to prefetch an upcoming day's first page before it was reached, which meant how many
  days actually got warmed up varied wildly with day density (a day with hundreds of photos vs. a
  handful). Now, whenever a day's own sentinel intersects, the callback also explicitly calls
  `loadDayPage` for the day 2 positions further down in `dayIndex.days`' order — a steady 2-day
  look-ahead buffer regardless of how many photos are in any given day, on top of (not instead of)
  the existing pixel margin. Regression-tested in `PhotoGrid.test.jsx` (a local
  capturing-`IntersectionObserver` stub, since the shared jsdom one in `test/setup.js` never fires
  its callback) — covers both the look-ahead call and not looking past the end of the day list.
- Confirmed already implemented (check-in, 2026-09-11 — no code change needed, TODO description
  was stale): fixed 4-column mobile/Android photo grid. Web already used `grid-template-columns:
  repeat(4, 1fr)` (not `auto-fill`/`minmax`) at both the `≤768px` and `≤480px` breakpoints in
  `App.css`; Android's `PhotoGridContent.kt` already used `GridCells.Fixed(4)` (not `Adaptive`).
  Also already implemented as part of the same mobile-grid work: the duplicate-cluster badge's web
  version already shows icon + count only on mobile (`PhotoGrid.jsx`'s `.duplicate-badge-label`
  span, CSS-hidden at the mobile breakpoint; `messages["similarLabel"]` instead of the combined
  `similarPhotosBadge` template, which `DuplicateClusterModal.jsx`'s own title badge still uses
  unchanged) — only the Android side of that item (no duplicate-cluster UI there at all yet)
  remains open.

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
  - A new extra step (`PersonMergeSuggestionService`), run right after each
    user's own face-to-person matching/clustering inside `groupFacesForUser`, compares every
    existing person's overall (all-years-averaged) embedding directly against every other
    person's — much stricter threshold than ordinary face-to-person matching — and upserts a
    `PersonMergeSuggestionEntity` row for any pair similar enough to plausibly be the same real
    person (dropping a row that no longer clears the bar, e.g. after an unlink). Never merges
    anything itself. Library page: `PersonMergeSuggestionBanner.jsx` shows a notice whenever
    `GET /api/faces/persons/merge-suggestions/count` reports at least one suggestion; its popup
    (`PersonMergeSuggestionModal.jsx`) shows one pair at a time with Merge (the existing
    `/persons/merge` two-person flow, unchanged), Next, and Close. A merge discards any suggestion
    naming either person (the deleted source via FK cascade, the surviving target explicitly,
    since its embedding just changed) so the next run recomputes it fresh.
  - Fixed a real production performance bug: the merge-suggestions endpoint used to be one flat,
    unpaginated list of EVERY live suggestion, each entry embedding both persons' full base64
    cover-face thumbnails (`FaceServiceImpl#mapToDTO`) — even though the popup only ever shows one
    pair on screen at a time. A real library with many suggestions turned that into a ~16MB,
    2+ second response for a banner that, until opened, only needs a count. Split into
    `GET .../merge-suggestions/count` (cheap, no face images at all) for the banner text, and
    `GET .../merge-suggestions?page=&size=` (the frontend always asks for `size=1`) for the popup —
    `PersonMergeSuggestionRepository.findBySpaceIdOrderBySimilarityDesc` and
    `PersonMergeSuggestionServiceImpl` now take a `Pageable`; `usePersonMergeSuggestions.js` fetches
    one suggestion at a time instead of holding the whole list client-side.
  - `GET /api/faces/persons` pagination fixed on the frontend: `usePersons.js` now tracks
    page/hasMore state and appends pages via `loadMore()`, driven by an `IntersectionObserver`
    sentinel in `Persons.jsx` (same infinite-scroll idiom as `PhotoGrid.jsx`'s day-indexed grid) —
    no more silent 50-person cap.
  - Fixed a real production bug: merging two people (`PersonRecognitionServiceImpl.
    mergeFacesIntoTarget`, both the Persons-page merge and the suggestion-banner's Merge button)
    could throw a `uk_people_cover_face_id` unique-constraint violation. `recalculateCoverFace`
    picks the target's new cover face from ALL its faces, including the ones just moved over from
    `source` — if the pick landed on the exact face that was `source`'s own cover face, saving
    `target` collided with `source`'s still-in-place `cover_face_id` (source wasn't deleted until
    afterward). Fixed by nulling and flushing `source`'s cover face right after its faces move,
    before `target`'s new one is computed/saved. Regression test:
    `PersonRecognitionServiceTest.mergePersonsSucceedsWhenSourcesCoverFaceBecomesTheTargetsNewCoverFace`.
  - Added `FaceEntity.status` (`NEW`/`OLD`, migration `V3__add_face_status.sql`) so
    `groupFacesForUser` stops re-running the expensive existing-person match against every person
    for the same known-unmatched face on every ~30s run forever. See `specification.md` §4.3 for
    the current design; `PersonRecognitionServiceImpl.updatePerson`/`addPerson` flip a face to
    `OLD` when they attach it to a person, `groupFacesForUser` flips it explicitly when a run's
    clustering pass leaves it an unresolved singleton. `CandidateClusterer` itself is untouched —
    `OLD` faces are still fed into it every run so a later `NEW` face can still complete a cluster
    with earlier runs' leftovers (regression-tested in `FaceRecognitionServiceImplTest`).
  - Fixed the Persons page (`GET /api/faces/persons`) ballooning to ~70MB/7s to load (reported
    2026-09-11). Root cause: `FaceDetectionServiceImpl` saved each detected face's UI thumbnail at
    its full original-photo crop resolution (detection runs against the full-res photo, not the
    resized-for-hashing copy) as a lossless PNG, mismatched with the "data:image/jpeg;base64,"
    prefix `FaceServiceImpl.mapToDTO` already labeled it with — a single crop could be 1+MB, times
    ~50 people/page. Fixed going forward by resizing to `FACE_THUMBNAIL_MAX_SIZE` (300px, this
    thumbnail is only ever shown at ~100px CSS size) and encoding as a real compressed JPEG via
    Thumbnailator (`FaceDetectionServiceImpl.generateFaceThumbnail`, unit-tested in
    `FaceDetectionServiceImplTest` against a synthetic crop — no test exercises the real ONNX
    pipeline). Also fixed a real N+1 (`PersonRepository`'s list query now `left join fetch`es
    `coverFace` instead of lazy-loading it once per person) and, per explicit user request, split
    the list endpoint's response down to a new lean `PersonSummaryDTO` (id/name/coverPhoto only) —
    `photoCount` stays on the fuller `PersonDTO` used by the Person detail page and the merge-
    suggestion/rename-collision pickers, which do render it. **Not yet done**: already-stored face
    thumbnails from before this fix remain oversized PNGs until re-processed — no migration/
    regeneration path exists yet (would need to re-crop from each `FaceEntity`'s stored photo +
    x/y/width/height and re-save via the same `generateFaceThumbnail`, without touching embeddings/
    clustering) — worth doing if the reported account still sees a large response after this ships.
  - Confirmed already implemented (user check-in, 2026-09-11 — these had been sitting as open TODO
    items but the code already covers them): blurry-face rejection at detection time
    (`FaceDetectionServiceImpl.BLUR_VARIANCE_THRESHOLD`, variance-of-Laplacian via `BlurDetector`,
    regression-tested in `BlurDetectorTest`); Persons sort (named-first, then `photoCount DESC` —
    `PersonRepository.findAllBySpaceIdOrderByNamedFirstThenPhotoCountDesc`'s explicit `ORDER BY
    CASE...`); Person detail page back button (`Header.jsx`'s `pathname.startsWith('/persons/')` →
    `backToPersons` button, same pattern as Albums/Spaces); Albums grid search
    (`AlbumGridDisplay.jsx`'s case-insensitive client-side `filteredAlbums`, since `GET
    /api/albums` already returns the full unpaginated list).

### Sharing
- Full design in `specification.md` §4.4. Originally broken end-to-end (wrong URI parsing, guest
  sessions hitting `@MainSpaceLimited` lookups that 500, no session ever established for
  unprotected links, a frontend/backend field-name mismatch, expiry never actually checked) —
  rewritten rather than patched: `/share/{userMd5}/{name}` addressing, a `ROLE_SHARE` guest
  session with its own `ShareAccessFilter` authorization boundary, guest-safe unfiltered lookups
  kept separate from the authenticated app's space-filtered ones, and a guard against clobbering
  an owner's real session when previewing their own link.
- `SharedAlbumView` later moved off its own flat page-by-page fetch (`useSharedAlbumPhotos`,
  deleted) onto the shared `useDayIndexedPhotos` day-index timeline, via a new explicit
  `{ type: 'shareAlbum', albumId }` target and two guest-scoped endpoints
  (`GET /api/albums/share/{id}/days` + `/photos/day/{day}`, `ShareAccessFilter` regex widened to
  allow those tails). Fixes the App-level `useDayIndexedPhotos` firing `/api/photos/days` (a
  library endpoint no `ROLE_SHARE` guest can call) on every share-link page load.

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
- **Root-caused the flaky `PhotoServiceTest.downloadSingleAndMultiplePhotosSetContentDispositionAndZipEntries`**
  (reported 2026-09-09, investigated once already and left open — see that commit's notes; picked
  back up 2026-09-11 on a hunch this was related to real `@Scheduled` background task execution
  racing the test). Two earlier hypotheses were ruled out on re-examination: (1) `generateTestJpeg`'s
  MD5-collision risk on `PhotoRepository.findByFileHash` routes a duplicate upload to `TaskStatus
  .IGNORED`/`TRASHED`, which `waitForTaskCompletion` doesn't treat as terminal, so a real collision
  would time out, not produce a wrong zip size; (2) `TaskWorkerManagerConcurrentPollTest`'s stress
  test (64 threads hammering `pollLightPendingTasks()`) never reproduced the pre-existing
  `queuedTasks` double-submission race either, even against the unfixed code. **Actual cause: a test
  bug, not an application bug.** `PhotoController#downloadMultiple` returns
  `ResponseEntity<StreamingResponseBody>`, which Spring MVC processes as an ASYNC request —
  `MockMvc.perform()` returns once that async processing STARTS, not once the streaming body has
  actually finished writing (the standard fix is the `asyncDispatch()` round-trip, which this test
  was missing entirely). Confirmed by reproducing on demand: a throwaway test hammering the exact
  same request pattern under artificial busy-thread CPU load reliably got an EMPTY zip (0 entries)
  without `asyncDispatch()`, and 0 failures across 40 iterations once it was added — this is also
  why it "passed reliably in isolation" (an idle CPU lets the async write finish before the test
  thread reads the response almost every time) but flaked under a full suite's CPU contention (many
  other tests'/schedulers' background threads delaying that same race). Fixed by adding the
  `andExpect(request().asyncStarted())` + `asyncDispatch()` pair before reading the zip's content -
  the only endpoint in the app returning `StreamingResponseBody`, so no other test needed the same
  fix.

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
- Added an Admin > Tasks page (`/admin/tasks`, `TaskManagement.jsx` + `useTaskManagement.js`):
  paginated, filterable (status/type) list of every `TaskEntity`, with per-row and mass-selection
  ("select all restartable on this page") restart — resets a task back to `PENDING` regardless of
  its current status, clearing any error. Backend: `AdminController`/`AdminServiceImpl`'s
  `getTasks`/`restartTask`/`restartTasks`, `TaskRepository.findByOptionalStatusAndTaskType`
  (optional status/type filters, no hardcoded `ORDER BY` — sorting is left entirely to the
  caller's `Pageable` instead), `TaskAdminDTO`. See specification.md §5.7.
- Added a `PARKED` `TaskStatus` plus per-row Park/Unpark buttons on the Tasks page: Park flips a
  task to `PARKED` (every other field untouched, so it's simply never selected by any
  `poll*PendingTasks` query, all of which filter on `PENDING`); Unpark sets it straight back to
  `PENDING`. Backend: `AdminService`/`AdminServiceImpl.parkTask`/`unparkTask`,
  `AdminController`'s `POST /api/admin/tasks/{id}/park` / `.../unpark`.
