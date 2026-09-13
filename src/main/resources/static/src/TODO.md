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
  (`Header`, grids, modals) — worth a dedicated pass if there's appetite. **Step 1 (the regression
  safety net) is done (2026-09-11) — the refactor itself is still open.** `App.test.jsx` is a real
  root smoke test (renders the actual `<App/>` inside a `MemoryRouter`/`LocaleProvider`, mocking
  every mount-time endpoint the Library route hits: `/api/user/me`, `/api/photos/days`,
  `/api/photos/day/{day}`, `/api/faces/persons/merge-suggestions/count`, `/api/photos/memories`,
  plus `/api/albums` for the menu-switch case — a capturing-`IntersectionObserver` stub fires
  `PhotoGrid.jsx`'s day-sentinel manually, same technique as `PhotoGrid.test.jsx`'s own look-ahead
  tests, since jsdom's shared stub never calls back on its own). Covers exactly the two scenarios
  named below: selecting a photo shows "N selected" and the right bulk-action button in `Header`
  (proving `selectedIds` AND the derived `photoSelection` both reach it correctly), and navigating
  Library → Albums clears the selection (`App.jsx`'s path-change effect). Next: do the
  consolidation, using this smoke test to catch wiring regressions as it lands — still worth a
  dedicated `Header.jsx` test too if the refactor touches its prop shape directly.
- **Large files not yet reviewed for internal duplication**: `Lightbox.jsx` (723 lines),
  `UploadManager.jsx`, `useAlbumManagement.js` (largest hook — remember Album/Space are NOT
  interchangeable domain concepts; any shared logic factored out must stay presentation-only).
  `UploadManager.jsx`/`useAlbumManagement.js` already had real regression coverage (checked
  2026-09-11) and are safe to review as-is. **`Lightbox.jsx`'s test gap is now closed too
  (2026-09-11)** — `Lightbox.test.jsx` covers the three areas flagged as prerequisites: the
  face-box overlay position calculation (asserts exact `.face-box` `left`/`top`/`width`/`height`
  against a stubbed letterboxed image+container, since jsdom does no real layout - naturalWidth/
  Height and clientWidth/Height are stubbed directly on the DOM nodes via `Object.defineProperty`),
  prev/next/Escape keyboard navigation (including not overflowing/underflowing past either end of
  the photo list), and pan/zoom clamping (`clampPan`, verified by dragging far past the actual
  overscroll range for a given zoom and asserting the rendered `transform`'s `translate()` stays
  within it, plus confirming no pan at all is possible at the default 1x zoom). None of image
  editing (crop/rotate/zoom via `react-cropper`), face rename/merge, or the wheel-zoom math itself
  beyond one step are covered — worth another pass if the refactor ends up touching those. Ready
  for the actual duplication review across all three files now.
- **No CI workflow runs `npm test`/`npm run lint` automatically** — the frontend suite is still
  local-only. (`mvn test` now runs on every PR into master via
  `.github/workflows/docker-image.yml`'s `test` job, and is required to pass before the Docker
  image job — which itself is now `workflow_dispatch`-only, not automatic on push to master.) Worth
  wiring up the frontend suite too if regressions matter enough to gate merges on it as well.
  Reminder: once this workflow exists on master, its `test` job still needs to be added as a
  required status check under Settings > Branches for master (branch protection isn't something a
  workflow file alone can set) for PRs to actually be blocked on failure.
- **Regression tests worth adding, not yet written**: `UploadManager.jsx`'s "PENDING keeps
  polling" fix (needs fake timers + mocking the chunk-upload flow). (The root `App.jsx` smoke test
  that used to be listed here is now step 1 of the `App.jsx` selection-state item above, since
  that refactor is what actually needs it.)
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
- **Fixed duplicate `.../photos/day/{day}` requests, several per day** (2026-09-12, reported on a
  Person's Photos tab, but the bug lives in the shared `useDayIndexedPhotos.js`/`PhotoGrid.jsx`
  machinery every day-indexed view - Library/Album/Space/Garbage/Person - uses). Root cause:
  `loadDayPage`'s re-entrancy guard read `dayPageStateRef`, a ref only kept in sync with React
  state by its own separate `useEffect` - one render behind. PhotoGrid's shared day-sentinel
  `IntersectionObserver` can call `loadDayPage` for the SAME day more than once in one synchronous
  burst (its own 2-days-ahead look-ahead prefetch landing on a day that's also directly
  intersecting, and/or the observer tearing down/recreating every time ANY OTHER day's fetch
  completes - its effect depends on `photosByDay`/`dayPageState` - and re-evaluating still-visible
  sentinels) - all before a render/effect round-trip could sync that ref, so the guard couldn't
  see the first call's fetch as "in flight" yet and let duplicates straight through. Reproduced
  directly (calling `loadDayPage` twice back-to-back with no `await` between them fired two
  identical GETs) before fixing. Fixed with a second ref (`inFlightDaysRef`, a plain `Set`) mutated
  synchronously the instant a fetch starts/finishes - no render needed for the very next call to
  see it. Regression-tested
  (`useDayIndexedPhotos.test.js#doesNotDoubleFetchWhenLoadDayPageIsCalledTwiceForTheSameDayBeforeTheFirstResolves`) -
  every prior test in that file always awaited one `loadDayPage` call before making the next,
  which is exactly why this never surfaced there.

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
  - **Person detail "Faces" tab** (2026-09-12): `PersonDetail.jsx` now has Photos/Faces tabs -
    Photos is the existing day-indexed `PhotoGrid`; Faces is a new paginated grid
    (`PersonFacesGrid.jsx`/`hooks/usePersonFaces.js`) of the person's own distinct face crops,
    newest-first, each unlinkable in place - reused the already-existing
    `GET /api/faces/person/{id}/faces` + `DELETE /api/faces/{id}/person` endpoints as-is, no new
    backend surface needed. Found and fixed a real bug surfaced while checking that unlink
    correctly respects `FaceLinkExclusionEntity`: `FaceServiceImpl.detachFaceFromPerson` recorded
    the exclusion but never reset the face's `status` back to `NEW`, and a linked face is
    essentially always `OLD` by the time it's unlinked - `groupFacesForUser` only ever checks
    exclusions for a `NEW` face, so unlinking silently orphaned the face forever (exclusion
    recorded but never consulted, and never eligible for automatic re-matching to a different
    person either). Fixed + regression-tested end-to-end
    (`FaceRecognitionServiceImplTest#aFaceUnlinkedFromAPersonNeverReattachesThereButCanStillMatchADifferentPerson`).
  - **`mergeInto` direction changed to face-count-based** (2026-09-12, product decision): used to
    be fixed-direction (the explicitly-renamed/`targetPersonId` person always survived); now
    mirrors `mergePersons` and keeps whichever side has more faces, regardless of which id was
    passed as target vs. source. Tests updated (`PersonRecognitionServiceTest
    #mergeIntoKeepsThePersonWithMoreFacesRegardlessOfWhichIdWasPassedAsTarget`). This surfaced a
    real bug in the caller: `FaceServiceImpl.renamePerson` used to assume the person it renamed
    would always be the survivor and re-fetched that same id afterward - when a merge candidate
    turned out to have more faces and "won" instead, that re-fetch 404'd and the confirmed rename
    was silently lost. Fixed to track whichever id actually survives across the whole
    fold-in-candidates loop and apply the new name to it, whichever person that ends up being
    (`FaceServiceTest#renamingAPersonThatMergesIntoABiggerCandidateStillAppliesTheNewNameToTheActualSurvivor`).
  - **Production incident (2026-09-12): `PersonMergeSuggestionServiceImpl.refreshSuggestions` hung
    the whole app for hours, taking prod down completely.** Root cause: it ran one
    `findByPersonAIdAndPersonBId` query PER comparison pair inside a single long `@Transactional`
    method - fine for a handful of people, but a space with just 406 people is already
    `406×405/2 ≈ 82,200` pairs, and Hibernate's default auto-flush fires before every query,
    re-checking a persistence context that only ever grew (every suggestion saved earlier in the
    same run stayed managed). Effectively quadratic on top of the already-quadratic pair count -
    confirmed via a forced (`jhsdb jstack`, ptrace-based - the JVM couldn't reach an ordinary
    safepoint) thread dump showing the single dedicated `heavy-tasks.face-recognition-threads: 1`
    thread pinned at ~100% CPU for 3.5+ hours inside `SessionImpl.autoFlushIfRequired` on that one
    space's 83,836-ungrouped-face/406-person backlog, `TaskWorkerManager` re-submitting it every
    30s (`in-flight` never clearing). That one thread pegging a core starved the rest of the JVM
    (GC, then Tomcat) badly enough that even loopback TCP connections stopped completing -
    "container up, CPU/RAM graphs fine" masked a single-process 99% CPU spike until `docker compose
    top` was checked directly. Fixed by batch-loading this space's existing suggestions in ONE
    query up front (`PersonMergeSuggestionRepository.findBySpaceId`) into an in-memory `Map`
    instead of querying per pair - turns the per-pair auto-flush churn into a single flush at
    commit. Regression-tested (`PersonMergeSuggestionServiceImplTest`: create/update-in-place/
    delete-as-similarity-changes across repeated `refreshSuggestions` runs). Immediate mitigation
    while this shipped: `POST /api/admin/heavyTasks {"activate": false}` to stop face
    recognition/duplication from resubmitting until the fix was deployed.
  - **Admin > Tasks page: Park/Unpark button silently hidden behind Restart** (2026-09-12,
    reported right after the incident above). Not a regression in the Park/Unpark feature itself
    (`AdminServiceImpl`/`AdminController` unchanged, worked as designed) - `.task-table`'s Actions
    column was 8% wide with the shared nowrap/`overflow: hidden` cell rule (needed elsewhere for
    long UUIDs/error text), which silently clipped the second button out of view once a row also
    had a Restart button, rather than erroring or visibly truncating. Fixed in
    `UserManagement.css` (reused by `TaskManagement.jsx`): widened the Actions column (8% → 14%,
    taken from the error column) and let that one cell wrap its buttons onto a second line instead
    of hiding overflow. Confirmed visually (Playwright screenshot of a standalone harness using the
    real CSS file) before pushing, since this app's dev server needs a running backend to actually
    reach the Tasks page.
  - **Added Spring Boot Actuator for incident observability** (2026-09-12, direct follow-up to the
    hang above - diagnosing it required borrowing a JDK image for `jhsdb jstack` over a shared PID
    namespace and hand-rolled `/proc` CPU-tick scraping, since this app had zero
    Actuator/Micrometer before). `spring-boot-starter-actuator` added; `management.endpoints.web
    .exposure.include` deliberately curated (health/info/metrics/threaddump/heapdump/loggers, not
    `env`/`beans`/`configprops`); gated behind `hasRole("ADMIN")` in `SecurityConfig`, same pattern
    as `/api/admin/**`, and deliberately left on the same port as everything else (a separate
    `management.server.port` would run outside Spring Security's filter chain entirely -
    unauthenticated thread/heap dumps). New `HeavyTaskHealthIndicator`
    (`org.iphoto.health`) flips `/actuator/health` `DOWN` the moment a `FACE_RECOGNITION`/
    `CHECK_DUPLICATION` task has been `PROCESSING` for over 15 minutes - fast-alerting complement
    to `reclaimStuckProcessingTasks`' hourly, unconditional sweep. See specification.md §5.10.
    **Not done**: nothing is actually scraping `/actuator/metrics` yet (no Prometheus/Grafana wired
    up) - these are on-demand-only for now, and `/actuator/health`'s new DOWN signal isn't hooked
    to any alerting channel (email/Slack/etc.) either, just visible to whoever/whatever polls it.
    Also considered and deliberately not done: killing an individual stuck thread from outside the
    JVM - `Thread.stop()` always throws `UnsupportedOperationException` on JDK 20+,
    `Thread.interrupt()`/`shutdownNow()` is a no-op against non-cooperative code (exactly what this
    incident's stuck thread was, deep in Hibernate's flush internals), and sending `SIGKILL` to one
    OS thread of a multithreaded process kills the whole process on Linux regardless. The two real
    levers stay (1) bounding the algorithm itself (the batch-prefetch fix above) and (2) fast
    detection via this health indicator; true per-task kill-ability would need running heavy tasks
    in an isolated child process, a bigger change not attempted here.

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

### Frontend lint/dead-code cleanup (2026-09-11)
- Removed two confirmed-unused npm packages (`react-advanced-cropper`, `react-photo-album`).
- `Lightbox.jsx`: deleted the entirely dead `albumAllowed`/`setAlbumAllowed` state and its
  `GET /api/photos/{id}/space` fetch (set but never read anywhere in the file — git history traced
  it to a since-superseded early attempt at gating something the centralized
  `selectionCapabilities.js` capability-flag system now handles instead). This leaves
  `PhotoController#getPhotoSpace` backend-orphaned (no other frontend/Android caller) — not removed
  here, flagged as a possible following backend cleanup. Also dropped the now-unused
  `errorFetchingSpaceInfo` i18n key (both locales).
- `App.css`: deleted the dead `.library-timeline`/`.memory-card*`/`.timeline-browse`/
  `.timeline-year*`/`.timeline-month*` rules (confirmed zero JSX references) — leftovers from the
  deleted `LibraryTimeline.jsx` month-timeline UI, larger in extent than originally scoped (the
  whole contiguous block, not just the two classes first flagged) — plus the stale duplicate
  `.memories-strip` rule superseded by the real one `MemoriesBar.jsx` uses. Updated
  `AlbumGridDisplay.jsx`'s comment, which referenced the now-deleted `.library-timeline` by name.
- Triaged every `react-hooks/exhaustive-deps` warning (`npm run lint` is `--max-warnings 0`, so
  these were failing it outright): `App.jsx`'s three `dayIndexed`-object omissions and
  `UserManagement.jsx`'s mount-only `fetchUsers`/`fetchHeavyTasksActivated` omission were correct,
  intentional patterns already used elsewhere in the codebase, just missing the documenting
  `eslint-disable-next-line` comment (added). `UploadManager.jsx`'s `effectivePath`/
  `refreshAndMaintainScroll` in `handleUploadFiles`'s deps were genuinely unused inside that
  callback — removed. `PersonStrip.jsx` had a real, subtle bug: its explanatory comment before an
  `eslint-disable-next-line` pushed the actual disable onto the wrong line (a blank/comment line,
  not the dependency array two lines below), so the intended suppression silently did nothing and
  the real warning re-appeared elsewhere — fixed by moving the directive immediately above the
  array. `DuplicateClusterModal.jsx` had a stale, no-longer-needed disable directive (removed) and
  a real perf issue the linter caught: `members = item?.members ?? []` allocated a fresh empty
  array every render when `item?.members` was nullish, defeating `keeperIndex`'s own `useMemo` -
  fixed by memoizing `members` itself. `Lightbox.jsx`'s `handleNext`/`handlePrevious` were plain,
  non-memoized functions omitted from a keydown-listener `useEffect`'s deps despite closing over
  `photos`/`onNavigate` - a genuine stale-closure risk, not just a lint nitpick (the listener could
  keep firing against outdated photos/callback if those props changed without also changing one of
  the effect's other listed deps) - fixed by wrapping both in their own `useCallback` and adding
  them to the effect's deps. Also removed an unrelated, confirmed-unused `isDuplicatesPage` var in
  `Header.jsx` (`no-unused-vars`) found while running the same lint pass. `npm run lint` is now
  clean except one unrelated, out-of-scope `react-refresh/only-export-components` warning on
  `LocaleContext.jsx` (would need splitting that file's exports, not attempted here).
